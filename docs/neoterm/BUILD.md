# Build NeoTerm Milestone 1

Use a disposable Android test device/emulator. This milestone retains `com.termux`
and shared UID/bootstrap paths. It does not install alongside Termux. Signing keys
must match for updates/shared-UID plugins. Do not uninstall an existing Termux
installation to test without first exporting its data yourself.

## Pinned build inputs
- Stable upstream v0.118.3 (`5b657c6adf4304e5198951ce815fe0205dcac29c`).
- JDK 11 (verified with Temurin 11.0.32.1+1).
- Gradle 7.6.4 wrapper, distribution SHA-256 checked in wrapper properties.
- Android Gradle Plugin 7.4.2; Kotlin 1.8.22, JVM target 1.8.
- Android SDK platform 30, build-tools 30.0.3, NDK 22.1.7171670.
- Upstream min SDK 24 and target SDK 28 are retained.
- Bootstrap 2025.03.28-r1+apt-android-7 for four ABIs, with upstream pinned SHA-256
  values in app/build.gradle; downloads are checked before embedding.

AGP/Gradle were updated from upstream 4.2.2/7.2 because old lint cannot analyze
Kotlin 1.8 metadata. This is build tooling only; no package/runtime upgrade.
Dependencies have fixed versions. Byte-for-byte reproducibility across arbitrary
JDK/platforms is not claimed; use the same toolchain and compare APK hashes.
A forced rebuild in this workspace produced an identical APK SHA-256; details
and exact hashes are in VERIFICATION.md and APK-SHA256SUMS.
The upstream public dev_keystore.jks is for debug testing only, not production.

## Setup
Install Android command-line tools from https://developer.android.com/studio and:

```sh
sdkmanager 'platform-tools' 'platforms;android-30' 'build-tools;30.0.3' 'ndk;22.1.7171670'
sdkmanager --licenses
export JAVA_HOME=/path/to/jdk-11
export ANDROID_HOME=/path/to/android-sdk
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
./tools/neoterm-debug.sh
```

The script pins the milestone version/tag and builds one universal debug APK plus
the instrumentation APK. To build upstream-style ABI splits instead:

```sh
./gradlew :app:assembleDebug
```

Artifacts:
- script: app/build/outputs/apk/debug/termux-app_neoterm-m1-debug_universal.apk
- split command: app/build/outputs/apk/debug/termux-app_debug_{ABI,universal}.apk
- tests: app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

## Checks

```sh
./gradlew :neoterm-input:test testDebugUnitTest :app:lintDebug
adb devices -l
./gradlew :app:connectedDebugAndroidTest
```

Reports: neoterm-input/build/reports/tests/test, app/build/reports/tests,
terminal-emulator/build/reports/tests, app/build/reports/lint-results-debug.html.
Instrumentation does not substitute for the real IME matrix in TESTING.md.

## This managed workspace
JDK: /workspace/toolchains/jdk11. SDK: /workspace/toolchains/android-sdk.
No toolchain binaries or local.properties are committed. The network requires a
proxy. Java does not inherit HTTP_PROXY automatically; Gradle uses local
~/.gradle/gradle.properties with systemProp.http[s].proxyHost/Port. Test workers
also need the proxy and the managed CA trust store; this environment used:

```sh
export JAVA_HOME=/workspace/toolchains/jdk11
export JAVA_TOOL_OPTIONS='-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts -Dhttps.proxyHost=proxy -Dhttps.proxyPort=8080 -Dhttp.proxyHost=proxy -Dhttp.proxyPort=8080'
./tools/neoterm-debug.sh
```

These network settings are environment specific. Keep TLS verification enabled.
