# SpamBlocker
Android Call/SMS blocker. (Android 10+)

<img src="https://github.com/aj3423/SpamBlocker/assets/4710875/9d44afe7-2524-4b34-8bf3-ba285200bb5c" height="100">[<img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png"
     alt="Get it on F-Droid"
     height="80">](https://f-droid.org/packages/spam.blocker/)

Table of Contents
=================
   * [Screenshot](#screenshot)
   * [Target Audience](#target-audience)
   * [How it works](#how-it-works)
   * [Features](#features)
   * [Permissions](#permissions)
   * [Privacy](#privacy)
   * [FAQ](#faq)
   * [Language Support](#language-support)
   * [Donate](#donate)

# Screenshot
| Call        | SMS         | Setting     | Notification |
| ----        | ----        | ----        | ----         |
| <img src="https://github.com/aj3423/SpamBlocker/assets/4710875/7f03d0a0-d12e-4e1b-a064-2412fc1cee8e" width="200"> | <img src="https://github.com/aj3423/SpamBlocker/assets/4710875/ff1dd6c3-56dc-4f64-96a5-e7ca379af035" width="200"> | <img src="https://github.com/aj3423/SpamBlocker/assets/4710875/a86fff09-d30b-428e-866c-0f07b874d479" width="200"> | <img src="https://github.com/aj3423/SpamBlocker/assets/4710875/633e0e24-5ba0-44d7-90ec-09324081d37b" width="200">  |

# Target Audience
- :white_check_mark: For people who are more inclined to reject unknown calls
- For those who need to answer unknown calls, for example, salesmen or lawyers, etc:
  - :white_check_mark: If your carrier supports STIR attestation and it works well for you
  - :x: Otherwise, consider other blockers that rely on spam database

# How it works
It works without replacing your default Call/SMS app.
 - For call: <br>
 &ensp; It's the Caller ID app 
 - For SMS: <br>
 &ensp; It takes over the SMS notification, it only filters the notifications, the spam messages will still be present in the SMS app.
   > 💡 Please turn off the notification permission of the default SMS app in system settings, otherwise there will be double SMS notifications.


# Features:

| Filter   | It checks                                       |
| ----     | ----                                            |
| Contacts | Whether from a contact                     |
| STIR     | STIR attestation                                |
| Repeated | Whether the number has been calling repeatedly  |
| Dialed   | Whether the number has been dialed |
| Recent Apps | If some specific apps have been used recently, all calls are allowed.<br>Use case:<br>&emsp; You ordered Pizza online and soon they call you to refund.|
| Off Time  | A time period that always permits calls, usually no spams at night. |
| Regex Pattern | Some typical patterns:<br> - Any number: `.*` (the regex `.*` is equivalent to the wildcard `*` in many other apps) <br> - Exact number: `12345` <br> - Starts with 400: `400.*` <br> - Ends with 123: `.*123` <br> - Shorter than 5: `.{0,4}` <br> - Longer than 10: `.{11,}` <br> - Unknown number (it's empty string): `.{0}` or `^$`<br>  - Contains "verification": `.*verification.*` <br> - Contains any of the words: `.*(police\|hospital\|verification).*` <br> - Starts with 400, with leading country code 11 or not: `(?:11)?400.*` <br>- Extract verification code from SMS message: `code.*?(\d+)`<br><br> Ask AI to generate or explain a regex: <br>&emsp; "Show me regex for checking if a string starts with 400 or 200"<br> &emsp; Results in `(400\|200).*` |


# Permissions 

| Permission (all optional)   | Why                                                             |
| ----                   | ----                                                            |
| ANSWER_PHONE_CALLS     | Reject, Answer and Hang-up calls                                |
| POST_NOTIFICATIONS     | Show notifications                                              |
| READ_CONTACTS          | For matching contacts                                           |
| RECEIVE_SMS            | For receiving new messages                                      |
| READ_CALL_LOG<br>READ_SMS | For feature: Repeated Call/Dialed (check if it's repeated)   |
| PACKAGE_USAGE_STATS    | For feature: Recent Apps <br>For checking whether an app has been used recently  |
| READ_PHONE_STATE       | For block mode: Answer + Hang-up (monitor ringing state)  |

# Privacy
 No data collection
 - No internet access, works completely offline.
 - No external storage access, only accessable to scoped storage(the Downloads folder).
 - No communication with other app

# FAQ
 - [SMS notification doesn't work after app is killed](https://github.com/aj3423/SpamBlocker/issues/100)
 - [How to always block particular number regardless of how many times it repeats, or within OffTime, etc...](https://github.com/aj3423/SpamBlocker/issues/80#issuecomment-2176943329)
 - [Integrate spam database](https://github.com/aj3423/SpamBlocker/issues/96#issuecomment-2218233500)
 - [Android 9- support](https://github.com/aj3423/SpamBlocker/issues/38)
 - [Dual SIM support](https://github.com/aj3423/SpamBlocker/issues/72#issuecomment-2158981192)

# Language support

Languages are translated using Gemini AI([golang script](https://github.com/aj3423/SpamBlocker/blob/master/auto_translate/translate.go)), fire an issue for requesting a new language support.

# Donate

:heart:  https://aj3423.github.io/donate
