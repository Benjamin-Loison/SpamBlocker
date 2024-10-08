package spam.blocker.service.bot

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import org.json.JSONObject
import spam.blocker.R
import spam.blocker.config.Configs
import spam.blocker.db.CallTable
import spam.blocker.db.NumberRuleTable
import spam.blocker.db.RegexRule
import spam.blocker.db.SmsTable
import spam.blocker.db.SpamNumber
import spam.blocker.db.SpamTable
import spam.blocker.ui.setting.LabeledRow
import spam.blocker.ui.widgets.GreyIcon
import spam.blocker.ui.widgets.GreyLabel
import spam.blocker.ui.widgets.NumberInputBox
import spam.blocker.ui.widgets.Str
import spam.blocker.ui.widgets.StrInputBox
import spam.blocker.ui.widgets.SwitchBox
import spam.blocker.util.Algorithm.compressString
import spam.blocker.util.Algorithm.decompressToString
import spam.blocker.util.Csv
import spam.blocker.util.Now
import spam.blocker.util.Util
import spam.blocker.util.logi
import spam.blocker.util.resolvePathTags
import spam.blocker.util.resolveTimeTags
import spam.blocker.util.toStringMap
import java.lang.Exception
import java.net.HttpURLConnection
import java.net.URL

// When adding a new IAction type, follow all the steps:
//  - add to  `ActionType`
//  - implement it
//  - add to  `defaultActions`
//  - add to  `botModule` in BotSerializersModule.kt

// Only append to this list, do not modify existing ones
enum class ActionType {
    CleanupHistory,
    HttpDownload,
    CleanupSpamDB,
    BackupImport,
    BackupExport,
    ReadFile,
    WriteFile,
    ParseCSV,
    ImportToSpamDB,
    ImportAsRegexRule,
}

val defaultActions = listOf(
    HttpDownload(),
    CleanupSpamDB(),
    CleanupHistory(),
    BackupExport(),
    BackupImport(),
    ReadFile(),
    WriteFile(),
    ParseCSV(),
    ImportToSpamDB(),
    ImportAsRegexRule(),
)


// A list represents all input/output types, which is used to check whether two items are chainable.
enum class ParamType {
    None,
    String,
    ByteArray,
    RuleList
}

@Composable
fun NoConfigNeeded() {
    GreyLabel(text = Str(R.string.no_config_needed))
}

interface IAction {
    // the return value will be used as the input `arg` for the next Action
    // When it fails, it returns: <false, errorReasonString>
    fun execute(ctx: Context, arg: Any?): Pair<Boolean, Any?> // return <Success, output>

    fun type(): ActionType

    // A name displayed on the button
    fun label(ctx: Context): String

    // A brief string showing current settings
    fun summary(ctx: Context): String

    // Explains what this action does, used in balloon tooltip
    fun tooltip(ctx: Context): String

    // These two functions are for checking if two sibling actions are chainable
    fun inputParamType(): ParamType
    fun outputParamType(): ParamType


    // the resource icon id
    @Composable
    fun Icon()

    // This will be used on the automation editing popup dialog
    @Composable
    fun Options()
}


//val actionSaver = Saver<IAction, String>(
//    save = { it.serialize() },
//    restore = { it.parseAction() }
//)
//@Composable
//fun rememberSaveableAction(action: IAction): MutableState<IAction> {
//    return rememberSaveable(stateSaver = actionSaver) {
//        mutableStateOf(action)
//    }
//}

val actionsSaver = Saver<SnapshotStateList<IAction>, String>(
    save = { it.serialize() },
    restore = { mutableStateListOf(*it.parseActions().toTypedArray()) }
)

@Composable
fun rememberSaveableActionList(actions: List<IAction>): SnapshotStateList<IAction> {
    return rememberSaveable(saver = actionsSaver) {
        mutableStateListOf(*actions.toTypedArray())
    }
}

fun IAction.clone(): IAction {
    return this.serialize().parseAction()
}


fun IAction.serialize(): String {
    return botJson.encodeToString(PolymorphicSerializer(IAction::class), this)
}

fun String.parseAction(): IAction {
    return botJson.decodeFromString(PolymorphicSerializer(IAction::class), this)
}

fun List<IAction>.serialize(): String {
    return botJson.encodeToString(ListSerializer(PolymorphicSerializer(IAction::class)), this)
}

fun String.parseActions(): List<IAction> {
    if (this.isEmpty())
        return listOf()

    return try {
        botJson.decodeFromString(ListSerializer(PolymorphicSerializer(IAction::class)), this)
    } catch (_: Exception) {
        listOf()
    }
}

// return:
//  null - no need to draw the indicator
//  true - draw green indicator
//  false - draw red indicator
fun isPreviousChainable(curr: IAction, prev: IAction?): Boolean? {
    return if (prev == null) {
        if (curr.inputParamType() == ParamType.None)
            null
        else
            false
    } else {
        curr.inputParamType() == prev.outputParamType()
    }
}

fun isNextChainable(curr: IAction, next: IAction?): Boolean? {
    return if (next == null) {
        if (curr.outputParamType() == ParamType.None)
            null
        else
            false
    } else {
        curr.outputParamType() == next.inputParamType()
    }
}

// Returns whether there is any error in this chain.
fun List<IAction>.allChainable(): Boolean {
    for (i in this.indices) {
        val curr = this[i]

        val prev: IAction? = if (i == 0) null else this[i - 1]
        val prevOK = isPreviousChainable(curr, prev)
        if (prevOK == false) return false

        val next: IAction? = if (i == this.size - 1) null else this[i + 1]
        val nextOK = isNextChainable(curr, next)
        if (nextOK == false) return false
    }
    return true
}

// Return value:
//  - null, all success
//  - String, it failed, this String is the error reason.
fun List<IAction>.executeAll(ctx: Context): String? {
    var lastRet: Any? = null

    // Run until any action fails
    val anyError = this.any {
        val (succeeded, output) = it.execute(ctx, lastRet)
        lastRet = output
        !succeeded
    }
    return if (anyError)
        lastRet as String
    else
        null
}

// ---- All Actions ----
@Serializable
@SerialName("CleanupHistory")
class CleanupHistory(
    var expiry: Int = 90 // days
) : IAction {
    override fun execute(ctx: Context, arg: Any?): Pair<Boolean, Any?> {
        val now = Now.currentMillis()
        val expireTimeMs = now - expiry * 24 * 3600 * 1000

        logi("now clean up history db, deleting data before timestamp: $expireTimeMs")

        CallTable().clearRecordsBeforeTimestamp(ctx, expireTimeMs)
        SmsTable().clearRecordsBeforeTimestamp(ctx, expireTimeMs)

        return Pair(true, null)
    }

    override fun type(): ActionType {
        return ActionType.CleanupHistory
    }

    override fun label(ctx: Context): String {
        return ctx.getString(R.string.action_cleanup_history)
    }

    override fun summary(ctx: Context): String {
        val nDays = ctx.resources.getQuantityString(R.plurals.days, expiry, expiry)
        return ctx.getString(R.string.expiry) + ": $nDays"
    }

    override fun tooltip(ctx: Context): String {
        return ctx.getString(R.string.help_action_cleanup_history)
    }

    override fun inputParamType(): ParamType {
        return ParamType.None
    }

    override fun outputParamType(): ParamType {
        return ParamType.None
    }

    @Composable
    override fun Icon() {
        GreyIcon(R.drawable.ic_history_cleanup)
    }

    @Composable
    override fun Options() {
        NumberInputBox(
            intValue = expiry,
            label = { Text(Str(R.string.expiry_days)) },
            onValueChange = { newVal, hasError ->
                if (!hasError) {
                    expiry = newVal!!
                }
            }
        )
    }
}

@Serializable
@SerialName("HttpDownload")
class HttpDownload(
    var url: String = ""
) : IAction {

    @OptIn(DelicateCoroutinesApi::class)
    override fun execute(ctx: Context, arg: Any?): Pair<Boolean, Any?> {
        val connection = URL(url.resolveTimeTags()).openConnection() as HttpURLConnection
        var ret: Pair<Boolean, Any?> = Pair(false, null)

        val thread = GlobalScope.launch(Dispatchers.IO) {
            ret = try {
                connection.connect()
                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val byteArray: ByteArray = connection.inputStream.use { it.readBytes() }
                    Pair(true, byteArray)
                } else {
                    Pair(false, "HTTP: $responseCode")
                }
            } catch (e: Exception) {
                Pair(false, "$e")
            } finally {
                connection.disconnect()
            }
        }
        runBlocking {
            thread.join()
        }

        return ret
    }

    override fun type(): ActionType {
        return ActionType.HttpDownload
    }

    override fun label(ctx: Context): String {
        return ctx.getString(R.string.action_http_download)
    }

    override fun summary(ctx: Context): String {
        return "${ctx.getString(R.string.url)}: $url"
    }

    override fun tooltip(ctx: Context): String {
        return ctx.getString(R.string.help_action_http_download)
    }

    override fun inputParamType(): ParamType {
        return ParamType.None
    }

    override fun outputParamType(): ParamType {
        return ParamType.ByteArray
    }

    @Composable
    override fun Icon() {
        GreyIcon(iconId = R.drawable.ic_http)
    }

    @Composable
    override fun Options() {
        StrInputBox(
            text = url,
            label = { Text(Str(R.string.url)) },
            onValueChange = { url = it }
        )
    }
}

@Serializable
@SerialName("CleanupSpamDB")
class CleanupSpamDB(
    var expiry: Int = 1 // days
) : IAction {

    override fun execute(ctx: Context, arg: Any?): Pair<Boolean, Any?> {
        val now = Now.currentMillis()
        val expireTimeMs = now - expiry * 24 * 3600 * 1000

        logi("now clean up spam db, deleting data before timestamp: $expireTimeMs")
        SpamTable.deleteBeforeTimestamp(ctx, expireTimeMs)
        return Pair(true, null)
    }

    override fun type(): ActionType {
        return ActionType.CleanupSpamDB
    }

    override fun label(ctx: Context): String {
        return ctx.getString(R.string.action_cleanup_spam_db)
    }

    override fun summary(ctx: Context): String {
        val nDays = ctx.resources.getQuantityString(R.plurals.days, expiry, expiry)
        return ctx.getString(R.string.expiry) + ": $nDays"
    }

    override fun tooltip(ctx: Context): String {
        return ctx.getString(R.string.help_action_cleanup_spam_db)
    }

    override fun inputParamType(): ParamType {
        return ParamType.None
    }

    override fun outputParamType(): ParamType {
        return ParamType.None
    }

    @Composable
    override fun Icon() {
        GreyIcon(iconId = R.drawable.ic_db_delete)
    }

    @Composable
    override fun Options() {
        NumberInputBox(
            intValue = expiry,
            label = { Text(Str(R.string.expiry_days)) },
            onValueChange = { newVal, hasError ->
                if (!hasError) {
                    expiry = newVal!!
                }
            }
        )
    }
}

// Dump current settings to a ByteArray
@Serializable
@SerialName("BackupExport")
class BackupExport(
    private var includeSpamDB: Boolean = true
) : IAction {
    override fun execute(ctx: Context, arg: Any?): Pair<Boolean, Any?> {
        // Generate config data bytes
        val curr = Configs()
        curr.load(ctx, includeSpamDB)
        val compressed = compressString(curr.toJsonString())

        return Pair(true, compressed)
    }

    override fun type(): ActionType {
        return ActionType.BackupExport
    }

    override fun label(ctx: Context): String {
        return ctx.getString(R.string.action_backup_export)
    }

    override fun summary(ctx: Context): String {
        val yes = ctx.getString(R.string.yes)
        val no = ctx.getString(R.string.no)
        return ctx.getString(R.string.include_spam_db) + ": ${if (includeSpamDB) yes else no}"
    }

    override fun tooltip(ctx: Context): String {
        return ctx.getString(R.string.help_action_backup_export)
    }

    override fun inputParamType(): ParamType {
        return ParamType.None
    }

    override fun outputParamType(): ParamType {
        return ParamType.ByteArray
    }

    @Composable
    override fun Icon() {
        GreyIcon(iconId = R.drawable.ic_backup_export)
    }

    @Composable
    override fun Options() {
        LabeledRow(labelId = R.string.include_spam_db) {
            SwitchBox(includeSpamDB) { isTurningOn ->
                includeSpamDB = isTurningOn
            }
        }
    }
}

// Backup import from a ByteArray
@Serializable
@SerialName("BackupImport")
class BackupImport(
    private var includeSpamDB: Boolean = true
) : IAction {
    override fun execute(ctx: Context, arg: Any?): Pair<Boolean, Any?> {
        if (arg !is ByteArray) {
            return Pair(
                false, ctx.getString(R.string.invalid_input_type).format(
                    "ByteArray", arg?.javaClass?.simpleName
                )
            )
        }

        try {
            val jsonStr = decompressToString(arg)
            val newCfg = Configs.createFromJson(jsonStr)
            newCfg.apply(ctx, includeSpamDB)

            return Pair(true, null)
        } catch (e:Exception) {
            return Pair(false, "$e")
        }
    }

    override fun type(): ActionType {
        return ActionType.BackupImport
    }

    override fun label(ctx: Context): String {
        return ctx.getString(R.string.action_backup_import)
    }

    override fun summary(ctx: Context): String {
        val yes = ctx.getString(R.string.yes)
        val no = ctx.getString(R.string.no)
        return ctx.getString(R.string.include_spam_db) + ": ${if (includeSpamDB) yes else no}"
    }

    override fun tooltip(ctx: Context): String {
        return ctx.getString(R.string.help_action_backup_import)
    }

    override fun inputParamType(): ParamType {
        return ParamType.ByteArray
    }

    override fun outputParamType(): ParamType {
        return ParamType.None
    }

    @Composable
    override fun Icon() {
        GreyIcon(iconId = R.drawable.ic_backup_import)
    }

    @Composable
    override fun Options() {
        LabeledRow(labelId = R.string.include_spam_db) {
            SwitchBox(includeSpamDB) { isTurningOn ->
                includeSpamDB = isTurningOn
            }
        }
    }
}

@Serializable
@SerialName("ReadFile")
class ReadFile(
    var dir: String = "{Downloads}",
    var filename: String = "",
) : IAction {

    override fun execute(ctx: Context, arg: Any?): Pair<Boolean, Any?> {
        val path = dir.resolvePathTags()

        val fn = filename.resolveTimeTags()

        return try {
            val bytes = Util.readFile(path, fn)
            Pair(true, bytes)
        } catch (e: Exception) {
            Pair(false, "ReadFile: $e")
        }
    }

    override fun type(): ActionType {
        return ActionType.ReadFile
    }

    override fun label(ctx: Context): String {
        return ctx.getString(R.string.action_read_file)
    }

    override fun summary(ctx: Context): String {
        return "$dir/$filename"
    }

    override fun tooltip(ctx: Context): String {
        return ctx.getString(R.string.help_action_read_file)
    }

    override fun inputParamType(): ParamType {
        return ParamType.None
    }

    override fun outputParamType(): ParamType {
        return ParamType.ByteArray
    }

    @Composable
    override fun Icon() {
        GreyIcon(iconId = R.drawable.ic_file_read)
    }

    @Composable
    override fun Options() {
        Column {
            StrInputBox(
                text = dir,
                label = { Text(Str(R.string.directory)) },
                onValueChange = { dir = it }
            )
            StrInputBox(
                text = filename,
                label = { Text(Str(R.string.filename)) },
                onValueChange = { filename = it }
            )
        }
    }
}

@Serializable
@SerialName("WriteFile")
class WriteFile(
    var dir: String = "{Downloads}",
    var filename: String = "",
) : IAction {

    override fun execute(ctx: Context, arg: Any?): Pair<Boolean, Any?> {
        if (arg !is ByteArray) {
            return Pair(false, ctx.getString(R.string.invalid_input_type).format(
                "ByteArray", arg?.javaClass?.simpleName
            ))
        }
        val path = dir.resolvePathTags()
        val fn = filename.resolveTimeTags()

        return try {
            Util.writeFile(path, fn, arg)
            Pair(true, null)
        } catch (e: Exception) {
            Pair(false, "WriteFile: $e")
        }
    }

    override fun type(): ActionType {
        return ActionType.WriteFile
    }

    override fun label(ctx: Context): String {
        return ctx.getString(R.string.action_write_file)
    }

    override fun summary(ctx: Context): String {
        return "$dir/$filename"
    }

    override fun tooltip(ctx: Context): String {
        return ctx.getString(R.string.help_action_write_file)
    }

    override fun inputParamType(): ParamType {
        return ParamType.ByteArray
    }

    override fun outputParamType(): ParamType {
        return ParamType.None
    }

    @Composable
    override fun Icon() {
        GreyIcon(iconId = R.drawable.ic_file_write)
    }

    @Composable
    override fun Options() {
        Column {
            StrInputBox(
                text = dir,
                label = { Text(Str(R.string.directory)) },
                onValueChange = { dir = it }
            )
            StrInputBox(
                text = filename,
                label = { Text(Str(R.string.filename)) },
                onValueChange = { filename = it }
            )
        }
    }
}

/*
input: ByteArray
output: List<RegexRule>
 */
@Serializable
@SerialName("ParseCSV")
class ParseCSV(
    // a json string contains column map
    var columnMapping: String = "{}"
) : IAction {

    override fun execute(ctx: Context, arg: Any?): Pair<Boolean, Any?> {
        if (arg !is ByteArray) {
            return Pair(
                false, ctx.getString(R.string.invalid_input_type).format(
                    "ByteArray", arg?.javaClass?.simpleName
                )
            )
        }

        return try {
            val csv = Csv.parse(arg, JSONObject(columnMapping).toStringMap())

            val rules = csv.rows.map {
                RegexRule.fromMap(it)
            }

            Pair(true, rules)
        } catch (e: Exception) {
            Pair(false, e.toString())
        }
    }

    override fun type(): ActionType {
        return ActionType.ParseCSV
    }

    override fun label(ctx: Context): String {
        return ctx.getString(R.string.action_parse_csv)
    }

    override fun summary(ctx: Context): String {
        return columnMapping
    }

    override fun tooltip(ctx: Context): String {
        return ctx.getString(R.string.help_action_parse_csv)
    }

    override fun inputParamType(): ParamType {
        return ParamType.ByteArray
    }

    override fun outputParamType(): ParamType {
        return ParamType.RuleList
    }

    @Composable
    override fun Icon() {
        GreyIcon(iconId = R.drawable.ic_csv)
    }

    @Composable
    override fun Options() {
        StrInputBox(
            text = columnMapping,
            label = { Text(Str(R.string.column_mapping)) },
            onValueChange = { columnMapping = it }
        )
    }
}

/*
input: List<RegexRule>
output: null
 */
@Serializable
@SerialName("ImportToSpamDB")
class ImportToSpamDB : IAction {

    override fun execute(ctx: Context, arg: Any?): Pair<Boolean, Any?> {
        // It must be written like this, cannot be inlined in the `if()`, seems to be kotlin bug
        val inputValid = (arg is List<*>) && ((arg as List<*>).all { it is RegexRule })
        if (!inputValid) {
            return Pair(
                false, ctx.getString(R.string.invalid_input_type).format(
                    "List<Rule>", arg?.javaClass?.simpleName
                )
            )
        }

        return try {
            val now = System.currentTimeMillis()

            val numbers = (arg as List<*>).map {
                SpamNumber(peer = (it as RegexRule).pattern, time = now)
            }
            val errorStr = SpamTable.addAll(ctx, numbers)
            Pair(errorStr == null, errorStr)
        } catch (e: Exception) {
            Pair(false, e.toString())
        }
    }

    override fun type(): ActionType {
        return ActionType.ImportToSpamDB
    }

    override fun label(ctx: Context): String {
        return ctx.getString(R.string.action_import_to_spam_db)
    }

    override fun summary(ctx: Context): String {
        return ""
    }

    override fun tooltip(ctx: Context): String {
        return ctx.getString(R.string.help_action_import_to_spam_db)
    }

    override fun inputParamType(): ParamType {
        return ParamType.RuleList
    }

    override fun outputParamType(): ParamType {
        return ParamType.None
    }

    @Composable
    override fun Icon() {
        GreyIcon(iconId = R.drawable.ic_db_add)
    }

    @Composable
    override fun Options() {
        NoConfigNeeded()
    }
}

/*
input: List<RegexRule>
output: null
 */
@Serializable
@SerialName("ImportAsRegexRule")
class ImportAsRegexRule(
    var description: String = "",
    var priority: Int = 0,
) : IAction {

    override fun execute(ctx: Context, arg: Any?): Pair<Boolean, Any?> {
        if (!(arg is List<*> && arg.all { it is RegexRule })) {
            return Pair(
                false, ctx.getString(R.string.invalid_input_type).format(
                    "List<Rule>", arg?.javaClass?.simpleName
                )
            )
        }

        return try {
            // Join numbers to `11|22|33...`
            val combinedPattern = (arg as List<*>).joinToString("|") {
                (it as RegexRule).pattern
            }

            val newRule = RegexRule(
                pattern = "($combinedPattern)",
                description = description,
                priority = priority,
            )
            NumberRuleTable().addNewRule(ctx, newRule)

            Pair(true, null)
        } catch (e: Exception) {
            Pair(false, e.toString())
        }
    }

    override fun type(): ActionType {
        return ActionType.ImportAsRegexRule
    }

    override fun label(ctx: Context): String {
        return ctx.getString(R.string.action_import_as_regex_rule)
    }

    override fun summary(ctx: Context): String {
        val labelPriority = ctx.getString(R.string.priority)
        return "$labelPriority: $priority"
    }

    override fun tooltip(ctx: Context): String {
        return ctx.getString(R.string.help_action_import_as_regex_rule)
    }

    override fun inputParamType(): ParamType {
        return ParamType.RuleList
    }

    override fun outputParamType(): ParamType {
        return ParamType.None
    }

    @Composable
    override fun Icon() {
        GreyIcon(iconId = R.drawable.ic_regex)
    }

    @Composable
    override fun Options() {
        Column {
            StrInputBox(
                text = description,
                label = { Text(Str(R.string.description)) },
                onValueChange = { description = it }
            )
            NumberInputBox(
                intValue = priority,
                label = { Text(Str(R.string.priority)) },
                onValueChange = { newVal, hasError ->
                    if (!hasError) {
                        priority = newVal!!
                    }
                }
            )
        }
    }
}