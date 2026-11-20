# Rabbit Rocket

Android app (Kotlin, Compose). Package `io.github.arielfikru.rabbitrocket`.

## Ship

Default target: GitHub release on `arielfikru/rabbit-rocket`, APK asset `rabbit-rocket-vX.Y.Z.apk`.

1. Bump `versionCode` / `versionName` in `app/build.gradle.kts`, add the feature to `README.md`.
2. Build + tests (JDK 17, SDK in `local.properties` `sdk.dir=/opt/homebrew/share/android-commandlinetools`):
   `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew testReleaseUnitTest assembleRelease`
3. Signing: since v1.5.0 the release key is `~/.android/rabbitrocket-release.jks` (alias `rabbitrocket`,
   `CN=Rabbit Rocket, O=arielfikru`, cert SHA-256 `21f11c22782babaa441a3cbfec0d5d1c776a6c7562fca3b4bcf91f4075f99b07`),
   read via `~/.android/rabbitrocket-keystore.properties`; password also in Keychain service `rabbitrocket-keystore`.
   Keep a backup: losing it means users must uninstall again. (v1.0–1.4 used an older key, SHA-256 `8626182f…`, now lost.)
   Without the properties file the build falls back to the debug key. Check before releasing:
   `$ANDROID_HOME/build-tools/36.0.0/apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk`
4. Commit on `main`, `git push`.
5. Copy the APK to a file named `rabbit-rocket-vX.Y.Z.apk` first (`gh`'s `file#label` only sets a display label, the
   asset keeps the name `app-release.apk`), then `gh release create vX.Y.Z <that file> --notes-file ...` with Indonesian
   notes (feature list + "### Install" paragraph). Check with `gh release view --json assets`, download it, run apksigner.
6. Before releasing a browser change, try it on the emulator: AVD `rr35` (API 35 arm64), started with
   `$ANDROID_HOME/emulator/emulator -avd rr35 -no-window -no-audio`, then `adb install -r`, `adb shell input`, `adb exec-out screencap -p`.
