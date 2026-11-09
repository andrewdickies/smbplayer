# smbplayer

A simple Android TV video player built for a remote control. Supports SMB shares with custom ports, user-selected local / USB folders, M3U/M3U8 playlists and saved network streams. The UI is currently Chinese.

## Requirements and installation

- Android 6.0+, **32-bit ARM (armeabi-v7a)**. Compatible 64-bit devices can run it; 64-bit-only devices cannot.
- Download the debug APK artifact from a successful [Android workflow](https://github.com/andrewdickies/smbplayer/actions/workflows/android.yml), or build locally. Artifacts require a GitHub login and expire after 30 days.
- CI uses development signing; it may not update an existing installation with a different signature. Use your own stable signing key for distribution. Never commit keys.

## Features

- Five home entries: local storage, SMB, M3U/M3U8, streams and settings.
- Multiple saved sources; encrypted SMB passwords on the device; no automatic whole-device scan.
- Remote focus navigation, large text, file size / modification time and sorting.
- Playback controls: pause, playlist, aspect ratio, speed, subtitles, audio tracks, rotation / flip and sleep timer.
- Independent SMB forward disk cache: up to 1 GiB, eight workers. For indexed media, target around 60 seconds and refill below 30 seconds; other formats use a byte estimate.
- Seek / resume / frozen-picture recovery, with a single buffering overlay and download rate.

## Remote controls

| While controls are hidden | Action |
| --- | --- |
| OK | Pause / resume |
| Left / right | Seek; hold for larger jumps |
| Up | Show top and bottom bars |
| Down | Open playlist |
| Back | Press again within 2.4 seconds to leave |
| Touch video | Toggle bars without changing playback |

Back closes an open panel first. Touching / dragging the seek bar preserves the intended play / pause state.

## Build

Use JDK 17, Android SDK Platform 35 / Build Tools 35.0.0 and the included Gradle 8.10.2 wrapper. Set `JAVA_HOME` and `ANDROID_HOME`, or configure the SDK in Android Studio.

```sh
./gradlew :app:assembleDebug :app:lintDebug
python3 scripts/check.py
```

On Windows use `gradlew.bat` and `python`. Output: `app/build/outputs/apk/debug/app-debug.apk`.

There are nine offline host checks. Device / SMB checks need your own explicitly configured test fixtures; they are not packaged in the app. See [testing](../checks/README.md), [contributing](../CONTRIBUTING.md) and [architecture](ARCHITECTURE.md).

## Hardware limits

HDR, audio passthrough, CEC and recovery from vendor decoder failures depend on the actual TV and firmware. Phone tests cannot certify TV behavior. Some devices do not expose displayed-frame counters; decoded-frame fallback cannot prove physical display output. The cache does not promise a fixed duration for every format or a particular network throughput.

## License

Original project code: [GPL-3.0-or-later](../LICENSE). Dependencies retain their own licenses; see [third-party notices](../THIRD_PARTY_NOTICES.md). Uses VideoLAN LibVLC; not affiliated with VideoLAN and not the official VLC Android app.
