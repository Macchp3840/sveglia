# Sveglia Dura

An Android alarm clock you can't switch off with one finger. To silence it you have to get up and complete missions: solve math problems, shake the phone, or scan a barcode you've left far from your bed.

[Italiano](README.it.md)

<p align="center">
  <img src="docs/screenshots/home.png" width="30%" alt="Start screen: next departure and its details">
  <img src="docs/screenshots/settings.png" width="30%" alt="Edit: time wheel, days and missions">
  <img src="docs/screenshots/app-settings.png" width="30%" alt="Settings: language, alarm sound, rising volume">
</p>
<p align="center">
  <img src="docs/screenshots/math.png" width="30%" alt="Math mission">
  <img src="docs/screenshots/barcode.png" width="30%" alt="Barcode mission">
  <img src="docs/screenshots/check.png" width="30%" alt="Are you awake? check">
</p>

## Why

A normal alarm loses to a half-asleep thumb. Sveglia Dura (Italian for "tough alarm") is built to make that harder:

| Feature | How it works |
|---|---|
| **Math mission** | N problems like `7 × 8 + 6`. Get one wrong and you get a new one. |
| **Shake mission** | Shake the phone hard N times (accelerometer). |
| **Barcode / QR mission** | Register a code far from your bed (toothpaste, coffee, a QR taped in the bathroom). To stop the alarm you have to walk there and scan it. This is the mission that works best. |
| **Rising volume** | It starts a few minutes early (3 by default, 0 to 5) almost silent and reaches full volume exactly at the set time. Vibration starts at the set time. |
| **Your own sound** | Any ringtone on the phone or an audio file. If the file disappears, it falls back to the phone's alarm sound. |
| **Out loud, even with headphones** | With wired or Bluetooth headphones connected, the alarm still plays from the phone's speaker (Android 9 and newer). |
| **Locked volume** | The alarm volume is pushed back to maximum every second, even if you turn it down. |
| **No snooze, no Back** | Back doesn't close the screen. Leaving with Home keeps it ringing. |
| **Anti-relapse check** | A few minutes after you stop it, it asks "Are you awake?". No answer within 60 seconds and it rings again, missions included. |
| **Survives reboots and crashes** | If you turn the phone off or the system kills the app, the alarm and the "Are you awake?" check come back as soon as they can. |
| **Over the lock screen** | Turns the screen on and shows up full screen. |
| **Emergency exit** | Can't find the code? Replace it with 8 math problems: annoying, but you're never stuck. |
| **English and Italian** | Follows the phone's language, or pick one in Settings. |

## Design

The interface looks like a Solari split-flap departure board at a railway station, and your alarm is the train you have to catch. The start screen shows the next departure on flap tiles, with its days, missions, check and sound listed underneath. You set the time on a scroll wheel of flap tiles, the missions are listed like platforms, and on the alarm screen every digit sits on a tile that flips when it changes. The alarm screen shows one task at a time in large type, and the whole app is dark so it doesn't light up the room when you set it at night.

Font: [Archivo](https://github.com/Omnibus-Type/Archivo) (SIL Open Font License 1.1), bundled in `app/src/main/res/font/`.

## Install

Download `sveglia-dura-vX.Y.Z.apk` from the [latest release](https://github.com/Macchp3840/sveglia/releases/latest) and open it on your phone. Android will ask you to allow installs from that source (your browser or file manager) the first time. Later releases are signed with the same key, so they install over the old version and keep your settings.

To build it yourself you need JDK 17 and the Android SDK:

```bash
./gradlew assembleDebug
# APK in app/build/outputs/apk/debug/
```

Every push also runs the `Build APK` workflow, which keeps a debug APK as a GitHub Actions artifact. A tag like `v1.2.0` runs `Release APK`, which builds the signed APK and attaches it to a release.

Requirements: Android 8.0 or newer. Barcode scanning needs Google Play Services.

## First run

1. Allow notifications (without them the alarm can't appear on screen).
2. If a red warning shows up, tap **Fix** and grant the permission it asks for.
3. Tap **Edit**, roll the wheel to the time you want, pick the days and missions, turn the switch on and tap **Save**.
4. In **Settings** you can change the language, the alarm sound and how many minutes of rising volume you want.
5. Use **Test: ring in 10 seconds** and lock the phone to see how it really behaves (the test rings at full volume straight away).

## Known limits

- Powering off, *force stop* or uninstalling stops the alarm. No regular app can prevent that (a reboot does bring it back, see above).
- The shake mission can be done from bed. To actually get up, use the barcode.
- With the phone locked, scanning asks you to unlock the screen.
- If the "Are you awake?" check starts while you're using the phone, Android shows it as a notification instead of a full screen; if you ignore it, it rings again after 60 seconds anyway.
- Some brands (Xiaomi, Huawei, Oppo...) aggressively kill background apps: disable battery optimisation for this app.
- With rising volume on, the "next alarm" Android shows in the status bar and on the lock screen is the start of the rise, a few minutes before the time you set.

## Ideas for next versions

- Photo of a place: you photograph the sink once, and the alarm only stops when a new photo matches it.
- Walk N steps, counted by the step sensor.
- Repeat a colour sequence, or retype a long sentence without mistakes.
- Several alarms with different missions, for example a lighter one at the weekend.
- Evening lock: after a set hour you can't move or turn off tomorrow's alarm any more.
- Statistics, and a streak of days you got up straight away.

## Tech

Kotlin, Android Views, Material Components. `AlarmManager.setAlarmClock` for exact alarms, a `mediaPlayback` foreground service for sound and vibration, and the ML Kit code scanner, which runs through Google Play Services so the app doesn't need the camera permission. English and Italian strings, with the per-app language picker from AppCompat.

## License

[MIT](LICENSE)

Made by [Macchp3840](https://github.com/Macchp3840).
