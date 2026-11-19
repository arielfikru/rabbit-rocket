# Rabbit Rocket

Android app (Kotlin, Compose). Package `io.github.arielfikru.rabbitrocket`.

## Ship

Default target: GitHub release on `arielfikru/rabbit-rocket`, APK asset `rabbit-rocket-vX.Y.Z.apk`.

1. Bump `versionCode` / `versionName` in `app/build.gradle.kts`, add the feature to `README.md`.
2. Build + tests (JDK 17, SDK in `local.properties` `sdk.dir=/opt/homebrew/share/android-commandlinetools`):
   `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew testReleaseUnitTest assembleRelease`
3. Signing: release key is `CN=Rabbit Rocket, O=arielfikru`, SHA-256 `8626182fcc63c40a369a910bd3c3b552489539f3e2ad90f3f02ce50713d475b8`,
   read from `keystore.properties` or `~/.android/rabbitrocket-keystore.properties`. Without it the build falls back to the
   debug key, which can't install over earlier releases. Check before releasing:
   `$ANDROID_HOME/build-tools/36.0.0/apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk`
4. Commit on `main`, `git push`.
5. `gh release create vX.Y.Z app/build/outputs/apk/release/app-release.apk#rabbit-rocket-vX.Y.Z.apk` with Indonesian notes
   (feature list + "### Install" paragraph, like v1.4.0). Upload the asset under the name `rabbit-rocket-vX.Y.Z.apk`.
