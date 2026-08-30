# BlueSync

BlueSync turns one Android phone into a Bluetooth-controlled music **Host** and another into its **Remote**. The Host plays music - either its own local library or whatever's currently playing in another app (Spotify, Poweramp, YouTube Music, etc., via notification access) - and the Remote controls playback, browses the queue, and mirrors what's playing, all over a direct classic Bluetooth connection (no internet, no pairing account required).

## Features

- **Host / Remote roles**, swappable live without dropping the connection - either phone can flip to controlling the other mid-session.
- **Two host sources**: play BlueSync's own local library, or hook the currently-playing session of any other media app on the Host phone and relay its metadata/controls.
- **Fast discovery**: a BLE beacon lets phones find each other in about a second, falling back to classic Bluetooth discovery on devices that restrict BLE advertising.
- **Speaker mode**: any number of connected phones can join in and play the Host's current track locally, roughly in sync with each other - an ad-hoc multi-room speaker group. Native-library Host mode only (there's no way to capture another app's audio to relay it).
- Lazy-paginated queue browsing, a collapsing now-playing header, soft haptics, and a one-tap kill switch to instantly stop the connection, speaker mode, and playback.
- Stays connected across screen-off/Doze via a scoped wake lock and an optional battery-optimization exemption prompt.

## Requirements

- [Android Studio](https://developer.android.com/studio), or a standalone JDK 21 (Temurin) + the Android SDK if building from the command line.
- **JDK 21 specifically** - a newer default JDK on your machine will make Gradle's jlink step fail.
- Two Android devices (or one device + emulator) to actually test Host/Remote behavior - a single device can't talk to itself over Bluetooth.

## Run locally

1. Open Android Studio and open this project's directory.
2. Let Android Studio sync/fix Gradle as needed (make sure its configured JDK is 21, not whatever else may be installed).
3. Run the app on two physical devices (Bluetooth doesn't work in most emulators) - pick Host on one, Remote on the other.
4. On the Host, grant Notification Access if you want to control a third-party app instead of BlueSync's own library.

### Command line

```
gradle assembleDebug
```

This repo has no committed Gradle wrapper, so it builds with whatever `gradle` is on your `PATH` (CI uses 9.5.1). A `debug.keystore` is committed on purpose so local and CI builds share one signing key - reinstalling a CI build over a local one (or vice versa) won't hit a signature mismatch.

## CI

`.github/workflows/build.yml` builds a debug APK on every push to `main` and publishes it as a rolling "latest" GitHub Release (a direct, already-unzipped `.apk` download - the raw workflow artifact is zipped by GitHub's UI, which is easy to mistake for an installable file).
