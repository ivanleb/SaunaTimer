# Sauna Tracker

Galaxy Watch4 (Wear OS) app that times sauna rounds and records heart rate, plus a phone companion app for history, comparison and CSV export.

## Modules
| Module | What |
|---|---|
| `wear/` | Watch app: timer, live BPM, vibration alerts, recording, ambient mode |
| `mobile/` | Phone app: round list, graphs, overlay comparison, CSV share, settings |
| `shared/` | Models, Room database, alert logic, CSV, Data Layer sync, HR chart |

Both apps use the application id `com.sauna.tracker`. Sign them with the **same key**, otherwise the watch and the phone cannot talk to each other. Debug builds from the same PC are fine.

## Behaviour
- **Alerts** (defaults: 15 min, 140 bpm, editable on the watch and on the phone, kept in sync):
  - time limit reached → one long triple vibration
  - heart rate at or above the limit → short double vibration, repeated every 30 s while it stays there
  - vibrations use alarm priority, so they also come through in Do Not Disturb
- **Loss protection:** the round is saved when you press Start, and new samples are written every 5 s. If the watch overheats, the battery runs out or the app is killed, the round is marked *interrupted* the next time the app starts or the watch boots. It keeps everything up to the last save and is sent to the phone.
- **Sync:** each round is sent automatically when it ends. If the phone is out of range, the Data Layer queues it and delivers it later. You can also use "Send to phone" on the watch or the sync button on the phone.
- **Delete:** on the watch, open Recent rounds → round → Delete (tap twice). On the phone, use the trash icon on a round, or tick several rounds and tap Delete. A deletion is applied on both devices.
- Stop needs two taps, so wet fingers can't end a round by accident.

## Build
```
gradlew test assembleDebug
```
APKs: `wear/build/outputs/apk/debug/wear-debug.apk`, `mobile/build/outputs/apk/debug/mobile-debug.apk`.
SDK path is set in `local.properties`.

## Install
Phone (USB debugging on): `adb install -r mobile/build/outputs/apk/debug/mobile-debug.apk`

Watch:
1. Settings → About watch → Software → tap *Software version* 5× to turn on Developer options.
2. Developer options → turn on *ADB debugging* and *Wireless debugging*. The watch and the PC must be on the same Wi-Fi.
3. Wireless debugging → *Pair new device*, then on the PC run:
   ```
   adb pair <ip>:<pair-port>     (enter the code shown on the watch)
   adb connect <ip>:<port>
   adb -s <ip>:<port> install -r wear/build/outputs/apk/debug/wear-debug.apk
   ```
4. Open *Sauna Timer* on the watch and allow heart-rate and notification permissions on first Start.

## CSV format
One row per heart-rate reading:
`session_start,datetime,elapsed_s,bpm,hr_limit,time_limit_min,interrupted`
