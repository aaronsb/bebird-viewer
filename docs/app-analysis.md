# What the official Bebird app does on the network

This is a factual summary of what the official Android app sends, and when. It isn't a judgment of intent: a vendor app that talks to its own servers is normal, and several features, such as remote ear-canal assessment, are genuinely useful. The point is to let people make an informed choice. `bebird-viewer` itself contacts nothing but the scope.

## Method and scope

- **App:** `com.molink.john.hummingbird` version 6.4.54 (Android).
- **Method:** static analysis of the decompiled app. The app was not run and no Bebird server was contacted, so the findings describe what the code does, not observed network traffic. Behaviour can also change with server-side feature switches or a later app version.
- **Confidence:** high unless noted. "Automatic" means it happens without the user choosing a feature. "On use" means it only happens when the user takes a specific action.
- **References:** class and method names from the decompile are given as evidence.

## Summary

| Question | Answer |
|---|---|
| Is an account needed to use the camera? | **No.** The login screen has a "don't log in for now" option, and the live view doesn't check for a login. |
| Does the app contact Bebird's servers without a login? | **Yes.** It creates an anonymous server account keyed on a device identifier and uploads usage and session records. |
| Are photos or videos uploaded automatically? | **No.** Images are uploaded only when the user submits them in the Ear Canal Evaluation / expert feature, or sets a profile picture. |
| Is GPS location sent? | **No evidence of it.** Location permission is used for Wi-Fi scanning, which Android requires. The location fields in session records are left empty. |
| Can the scope reach the internet by itself? | **No.** The app never configures it to join another network, and anything that goes online goes through the phone. |
| Third-party SDKs active by default? | **Facebook** (app events) and **JPush** (push notifications) outside China. |

## Details

### Account and login

- Launch goes Splash → Intro → Login. The login screen's skip button (`LoginFirstActivity40`, label `login_temporary_donot`) goes straight to the main screen.
- The path into the live view (`MainActivityScience` → `WorkActivity40`) has no login or token check. An anti-counterfeit UUID check exists but is disabled by a build flag.
- **Anonymous account (automatic):** when the main screen opens or the "Mine" tab is shown without a token, the app registers a member keyed on a device identifier and requests an access token (`LoginImpl2.registerByMac`). The token is stored and reused.
- **Where that identifier comes from, in order of preference** (`WifiUtil`):
  1. an identifier reported by the scope;
  2. the phone's Wi-Fi MAC (not readable on modern Android);
  3. the OAID advertising identifier;
  4. otherwise a generated random ID, stored persistently.

  The account can later be rebound to the scope's identifier.

### Headers on every request

Every call to Bebird's API (`bcc.bebird.cn`) carries the device identifier, the phone's brand, model, device name and OS version, the app version, the language, and the account token (`RequestTokenInterceptor2`).

### Usage and session records (automatic)

- **Scope sessions:** after 60 s of live view the app saves a session record to a local database and updates it every 15 s (`WorkParentActivity`). Fields:
  - session start, end and duration;
  - product ID and scope UUID (or a hash of the device identifier);
  - the scope's activation date and firmware version;
  - screen orientation and phone model.

  Latitude/longitude fields exist but are always empty. Records are uploaded in batches when the app has internet: after the anonymous login, or when connectivity returns (`LoginImpl2.pushActionData`).
- **App usage:** start, end and duration of app use, the platform and the phone brand, uploaded straight after the session records (`UseActionBean`).
- **Other background calls:**
  - a connectivity check to `bcc.bebird.cn`;
  - a server-side feature-switch list, fetched after login;
  - for certain other Bebird models only, a warranty record.

### What happens when a scope connects

Connecting doesn't contact the server directly. Over the local UDP link the app:
- adjusts the scope's Wi-Fi transmit power;
- writes an activation date into the scope if it reports itself inactive.

The server only learns about the scope through the deferred session records above. Device-registration endpoints exist in the code but aren't called.

### Third-party SDKs

- **Facebook SDK:** initialised with app-event logging once the privacy agreement is accepted, and its startup provider is declared in the manifest. Auto-logged events and advertising-ID collection aren't turned off, so install/activation events probably go to Facebook with the advertising ID (medium-high confidence).
- **JPush (Jiguang) push notifications:** registers when the privacy agreement is accepted and notifications are allowed. The app then uploads the push registration ID with the device identifier. There's an in-app toggle for push. The JPush SDK contains its own device and network data collection whose runtime behaviour this analysis didn't determine (medium confidence).
- **Others:**
  - WeChat and Alipay SDKs are present for login and payments, and are used only when the user chooses them.
  - An OAID library reads the advertising identifier.
  - No Umeng, Bugly, Firebase Analytics or Google Analytics initialisation was found.

### Privacy agreement and region

At first launch the app checks whether it's running in China (`SplashActivity`):
- **In China,** it shows the privacy agreement screen.
- **Elsewhere,** it records the agreement as accepted and continues without opening that screen.

Features gated on the agreement, including the Facebook SDK, JPush and the advertising identifier, are therefore enabled from first launch outside China. This analysis didn't check whether the policy is presented some other way, such as the app store listing.

### Media and microphone (on use)

- Images are uploaded only from:
  - the Ear Canal Evaluation screen, when the user presses Commit;
  - the profile-picture setting.
- The live-view screen makes no network calls.
- The microphone is used only by a voice-recording feature inside the app's web view, when a web page starts it.
- Screenshots can be attached to feedback, at the user's initiative.

### Location

Location permission is used for Wi-Fi scanning and for connecting to the scope (Android requires it for both). No location lookups were found (`getLastKnownLocation`, `requestLocationUpdates`), and the location fields in uploaded records are empty.

### The scope and the internet

The firmware and app have commands to make the scope join another Wi-Fi network, but the app never calls them. The scope reports no HTTP server of its own. Its only data path is to the phone, and anything that reaches the internet goes through the app, queued locally until the phone is online.

### Other permissions

The manifest declares phone-state, log-reading and privileged phone-state permissions, but no app code was found reading IMEI, Android ID, phone number, SIM details, logs, the clipboard, contacts, or the list of installed apps. These permissions appear to exist for bundled SDKs, or to be unused.

## What this means in practice

- **To use the official app with minimal data sharing:**
  - skip login;
  - turn off notifications / push in the app's privacy settings;
  - don't use the evaluation or avatar features.

  It will still create an anonymous account, send device and phone details with each request, upload scope-session and app-usage records, and initialise the Facebook SDK outside China.
- **To avoid all of it,** use a client that talks only to the scope, such as this one.
