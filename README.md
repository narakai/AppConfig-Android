# AppConfig for Android

Update prompts (including forced updates), in-app notices, remote config, review prompts,
cross-promotion and user feedback with replies — for several apps from one small server.
Each app is identified by its `applicationId`.

## Modules

| Artifact | What it gives you |
|---|---|
| `appconfig` | `AppConfig`: fetches and caches everything, no UI, no networking dependency |
| `appconfig-compose` | `AppConfigHost()`, `FeedbackDialog()`, `AppPromoList()` (Material 3, follows your theme) |
| `appconfig-play` | `ReviewPrompt.maybeAsk(activity)` via Google Play In-App Review |

```kotlin
// settings.gradle.kts
maven("https://jitpack.io")

// app/build.gradle.kts
implementation("com.github.narakai.AppConfig-Android:appconfig-compose:0.1.0")
implementation("com.github.narakai.AppConfig-Android:appconfig-play:0.1.0") // optional
```

## Usage

```kotlin
// Application.onCreate
AppConfig.init(this, baseUrl = BuildConfig.APP_CONFIG_URL, tester = BuildConfig.DEBUG)

// Root of the UI: update dialogs, notices and feedback replies, one at a time
AppConfigHost(onLeaveApp = { /* e.g. suppress an app-open ad */ })

// Settings screen
if (showFeedback) FeedbackDialog(onDismiss = { showFeedback = false })
AppPromoList()

// Remote config (last known values work offline)
if (AppConfig.getBoolean("ads_enabled", true)) { ... }

// At a natural pause, not on launch
ReviewPrompt.maybeAsk(activity)
```

- **Forced update**: below the server's minimum version the dialog cannot be dismissed (back, tapping
  outside); it re-checks whenever the app comes back, and uses the last known minimum when offline.
- **Soft update**: Update / Later / Skip this version.
- **Testers**: debug builds (`tester = true`) and installs added on the server see test-only notices,
  config and version settings. Long-press the feedback dialog title to copy the install ID (it is not displayed).
- **Privacy**: a random install ID (not tied to the user, account or device) for daily active counts,
  version share and feedback replies. No IP addresses are stored by the reference server.

## Server API

The app needs a server with these endpoints (all JSON):

- `GET /v1/check?app=&platform=android&versionCode=&lang=&installId=[&test=1]`
  → `{ tester, update, review, config, notices, promos }`
- `POST /v1/ping` `{ app, platform, installId, versionCode, versionName, osVersion, lang }` — once a day
- `POST /v1/feedback` `{ app, platform, installId, category, message, contact, ... }` → `{ id }`
- `GET /v1/feedback?app=&installId=` → `{ feedback: [{ id, ts, category, message, status, reply, repliedAt }] }`

## License

MIT
