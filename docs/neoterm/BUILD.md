# Build standalone NeoTerm

The current development app uses `com.neoterm`, declares no shared UID, and starts
Android's `/system/bin/sh` in its private sandbox. Its metadata is designed for
installation alongside official Termux. Actual device coexistence must be checked;
see [STANDALONE-VERIFICATION.md](STANDALONE-VERIFICATION.md) for executed evidence.
Keep an existing Termux installation and its data in place when testing coexistence.
Run under the primary Android system user. Secondary users and work profiles are
blocked at startup while shared upstream path constants remain primary-user paths.

No Termux bootstrap is downloaded, embedded or installed by this app build. The
terminal-emulator module still compiles its PTY JNI library. The system shell
does not include apt/pkg, bash, Python, Node, SSH or AI coding CLIs. Future Linux
package builds use the separate procedure in [BOOTSTRAP-BUILD.md](BOOTSTRAP-BUILD.md).

## Pinned app build inputs

- Stable upstream v0.118.3 (`5b657c6adf4304e5198951ce815fe0205dcac29c`).
- JDK 17 (Temurin 17 in CI).
- Gradle 8.7 wrapper with checked distribution SHA-256.
- Android Gradle Plugin 8.5.2; Kotlin 1.9.24 with kapt/Hilt 2.51.1, JVM target 17.
- Android SDK platform 34, build-tools 34.0.0, NDK 22.1.7171670 for PTY JNI.
- Min SDK 26, compile/target SDK 34. Robolectric 4.12.2 JVM tests keep `sdk = [28]`.

Target SDK 34 obligations for the upstream code: `TermuxService` and
`RunCommandService` declare `foregroundServiceType="specialUse"` and start through
`ServiceCompat`; PendingIntents are `FLAG_IMMUTABLE`; the activity receiver is
`RECEIVER_NOT_EXPORTED`; notifications are posted only while
`POST_NOTIFICATIONS` is granted. The app does not request that runtime permission
yet, so on API 33+ the service notification stays hidden until the user enables it
in system settings.

The application ID is separate from the explicit `com.termux` Java/resource
namespace. Keep the source namespace and native symbol names intact when changing
app identity. Build environment variable names still use the upstream `TERMUX_`
prefix for compatibility with existing Gradle configuration.

## Setup and build

Install Android command-line tools from https://developer.android.com/studio and:

```sh
sdkmanager 'platform-tools' 'platforms;android-34' 'build-tools;34.0.0' 'ndk;22.1.7171670'
sdkmanager --licenses
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
./tools/neoterm-debug.sh
```

The script requires JDK 17, selects version `0.118.3-neoterm.standalone2`, runs
the JVM suite and builds a universal debug APK plus the instrumentation APK. Expected output paths
after a successful build:

- `app/build/outputs/apk/debug/neoterm_standalone-debug_universal.apk`
- `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`

For ABI splits with the default Gradle version/tag:

```sh
./gradlew :app:assembleDebug
```

That command names outputs `neoterm_debug_{ABI,universal}.apk` under the debug
output directory. Supported APK ABIs remain arm64-v8a, armeabi-v7a, x86_64 and x86;
successful execution on each ABI is a separate device requirement.

## Signing and installation

Android generates the debug keystore locally, normally at
`$HOME/.android/debug.keystore`. The checked-in upstream `app/dev_keystore.jks` is
unused by current debug builds. Keep the local key for developer upgrades; APKs
built with a different key cannot update the same installed NeoTerm package.
Release signing remains unconfigured and needs a protected production key before
public distribution. No signature compatibility with the archived M1 APK is needed
because the application IDs differ.

On a test device with official Termux already installed:

```sh
adb install -r app/build/outputs/apk/debug/neoterm_standalone-debug_universal.apk
adb shell am start -n com.neoterm/com.termux.app.TermuxActivity
```

These are acceptance instructions, not a claim that installation was executed.
Check distinct UIDs/data, both launchers, shortcut targets and preservation of
Termux's existing files using [TESTING.md](TESTING.md). Do not uninstall Termux
to make a failing coexistence test pass.

## GitHub checks

Pull requests to `main` and pushes to `main` run on Temurin JDK 17 with SDK
platform 34, build-tools 34.0.0 and NDK 22.1.7171670:
`./gradlew :app:lintDebug :app:testDebugUnitTest :app:assembleDebug --stacktrace`,
Python bootstrap-tool tests, then `tools/neoterm-debug.sh` (`:neoterm-input:test`,
all `testDebugUnitTest`, debug and instrumentation APKs). Lint must report zero
errors. Every APK in `app/build/outputs/apk/debug/` is uploaded. Reports and development APKs are workflow artifacts. The
workflow grants only repository read permission and does not publish releases or
deploy applications. Inherited upstream release-upload/JitPack workflows were
removed from this independent repository.

Each GitHub runner generates its own debug signing key. Its APK artifacts cannot
be assumed to update the privately delivered development APKs signed in the
original workspace. Stable developer/release signing is still pending.

## Checks and evidence

```sh
./gradlew :neoterm-input:test testDebugUnitTest :app:lintDebug
adb devices -l
./gradlew :app:connectedDebugAndroidTest
```

Reports: `neoterm-input/build/reports/tests/test`, `app/build/reports/tests`,
`terminal-emulator/build/reports/tests`, `app/build/reports/lint-results-debug.html`.
Record exact commands, source revision, outcomes, APK manifest/signature/size/hash
and device results in [STANDALONE-VERIFICATION.md](STANDALONE-VERIFICATION.md).
Historical M1 results and hashes are archived in [VERIFICATION.md](VERIFICATION.md)
and `APK-SHA256SUMS`; they provide no current standalone build evidence.

## Managed workspace setup

The workspace toolchain locations are a JDK 17 home (for example `$HOME/tools/jdk17`) and
`$HOME/Android/Sdk`; exporting `ANDROID_HOME` is enough. Toolchain binaries and `local.properties` are
not committed. Inspect the current managed environment's network/proxy/CA settings
before dependency downloads. Java does not automatically inherit `HTTP_PROXY`;
configure Gradle and test workers with the environment's supported proxy and CA
trust while keeping TLS verification enabled. A previous session's proxy values
or toolchain availability do not establish the current session's readiness.
