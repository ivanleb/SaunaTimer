# Sauna Pulse

A sauna round timer and heart-rate recorder for Wear OS watches (developed on a Galaxy Watch4), with a phone companion app for history, comparison and CSV export.

> **Status: work in progress.** The apps run and have been tested on a real Galaxy Watch4 and phone. Before the planned F-Droid release, the watch-to-phone sync is being moved from Google's Wearable Data Layer to direct Bluetooth, so the apps won't need Google Play Services.

## Features
- **Watch:** round timer, live BPM, a heart-rate graph, vibration alerts and an always-on (ambient) display during a round.
- **Alerts** (defaults are 15 min and 140 bpm; you can edit them on the watch or on the phone, and the two stay in sync):
  - Time limit reached: one long triple vibration.
  - Heart rate at or above the limit: a short double vibration, repeated every 30 s while it stays there.
  - Alerts use alarm priority, so they come through in Do Not Disturb.
- **Loss protection:** data is written every 5 s. If the watch overheats, the battery runs out or the app is killed, the round is kept up to the last save and marked *interrupted*.
- **Phone:** a list of rounds, graphs, an overlay comparison of selected rounds with a table, and CSV share.
- **Delete** wrongly recorded rounds on either device; the deletion applies on both.
- English and Russian.

## Privacy
Your data stays on your watch and phone. It leaves them only when you share a CSV yourself.
- The apps have **no internet permission**, no analytics, no ads and no accounts.
- Android cloud backup and device-to-device transfer are disabled.
- Watch and phone exchange data only directly with each other, when they are connected nearby.

## Modules
| Module | What |
|---|---|
| `wear/` | Watch app: timer, live BPM, alerts, recording, ambient mode |
| `mobile/` | Phone app: round list, graphs, comparison, CSV share, settings |
| `shared/` | Models, Room database, alert logic, CSV, sync, HR chart |

## Build
Requirements: JDK 17+ and Android SDK 36.
```
gradlew test assembleDebug
```
APKs are written to `wear/build/outputs/apk/debug/` and `mobile/build/outputs/apk/debug/`.

Release builds read the signing key from `keystore.properties`; see `keystore.properties.example`. That file and keystores are git-ignored.

## Install (sideload)
**Phone:** turn on USB debugging, then run `adb install -r mobile-debug.apk`.

**Watch:**
1. Settings → About watch → Software → tap *Software version* 5 times to enable Developer options.
2. In Developer options, turn on *ADB debugging* and *Wireless debugging*. The watch and the PC must be on the same Wi-Fi.
3. Wireless debugging → *Pair new device*, then on the PC run:
   ```
   adb pair <ip>:<pair-port>      # enter the code shown on the watch
   adb connect <ip>:<port>
   adb -s <ip>:<port> install -r wear-debug.apk
   ```
4. Open the app and allow the heart-rate and notification permissions on first Start.

## CSV format
One row per heart-rate reading:
`session_start,datetime,elapsed_s,bpm,hr_limit,time_limit_min,interrupted`

## Disclaimer
This is not a medical device. Leave the sauna whenever you feel unwell, whatever the app shows.

## License
[GPL-3.0](LICENSE)
