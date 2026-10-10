# BroStream AH for Android TV

A standalone Android TV app (no CloudStream). It lists videos from ManPorn, GayVids and GayPornTube using the same
engine as the CloudStream extension (`BroStream/src/main/kotlin`, compiled into this app), with a remote-friendly
home screen, search and a built-in player.

Adults only (18+). The app hosts no videos.

## Install on a TV box
Any one of these:
1. **Downloader app** (from the TV's app store): open it and enter the APK link.
2. **USB stick**: copy `BroStreamAH.apk` to it, plug it into the box and open it with a file manager app.
3. **ADB**: `adb connect <tv-ip>:5555` then `adb install -r BroStreamAH.apk`.
Allow "Install unknown apps" for the app you use when Android asks.

## Using it
- Up/Down move between rows, Left/Right move along a row. OK plays. **Hold OK** on a video to hide it for good.
- In the player: OK shows the controls, Left/Right seek, Back leaves. If a stream fails it tries the next quality.
- Settings > Run diagnostics shows whether the content filter works and whether each site is reachable.

## Building
Needs JDK 17 and the Android SDK (platform 35).
```
cd tv-app
./gradlew :app:assembleRelease
```
Set `BROSTREAM_KEYSTORE`, `BROSTREAM_KEYSTORE_PASSWORD` (and optionally `BROSTREAM_KEY_ALIAS`) to sign with your own key;
without them the debug key is used. Keep the same key for every release or the TV will refuse updates.
