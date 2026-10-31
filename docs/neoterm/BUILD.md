# Building commandroid-line

There are two runtime flavors, and every Android task is per flavor:

- **compat**: `com.commandroid_line`, targetSdk 28, Termux-compatible execution
  from the app's own prefix.
- **system**: `com.commandroid_line.system`, targetSdk 34, `/system/bin/sh` only.

[ADR-0001](ADR-0001-one-pty-terminal.md) explains why both exist.

## Pinned build inputs

- Upstream base: stable Termux v0.118.3 (`5b657c6adf4304e5198951ce815fe0205dcac29c`).
- JDK 17 (Temurin 17 in CI). Java and Kotlin JVM target 17.
- Gradle 8.7 wrapper, with the distribution SHA-256 checked in
  `gradle/wrapper/gradle-wrapper.properties`.
- Android Gradle Plugin 8.5.2, Kotlin 1.9.24. No kapt or Hilt.
- Android SDK platform 34, build-tools 34.0.0, and NDK 22.1.7171670 for the PTY
  JNI library (`ndkVersion` in `gradle.properties`).
- minSdk 26 and compileSdk 34 for both flavors. targetSdk is 28 for compat
  (`compatTargetSdkVersion`) and 34 for system (`targetSdkVersion`).
- Robolectric 4.12.2 for the app JVM tests, with `sdk = [28]`.

## Setup

Install the Android command-line tools from https://developer.android.com/studio,
then:

```sh
sdkmanager 'platform-tools' 'platforms;android-34' 'build-tools;34.0.0' 'ndk;22.1.7171670'
sdkmanager --licenses
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
```

On Windows, use `gradlew.bat` and set the same variables in the environment.
`local.properties` and the toolchains are not committed.

## Building each flavor

```sh
# system flavor: Android system shell only, no bootstrap involved
./gradlew :app:assembleSystemDebug

# compat flavor without a bootstrap: installs, shows a notice, uses /system/bin/sh
./gradlew :app:assembleCompatDebug

# compat flavor with a bootstrap built from source for com.commandroid_line
./gradlew :app:assembleCompatDebug -PcommandroidBootstrapDir=/path/to/bootstraps

# instrumentation APKs
./gradlew :app:assembleCompatDebugAndroidTest :app:assembleSystemDebugAndroidTest
```

The `-PcommandroidBootstrapDir` directory must contain at least one of
`bootstrap-aarch64.zip`, `bootstrap-arm.zip`, `bootstrap-i686.zip` or
`bootstrap-x86_64.zip`. If it contains none, the build fails. The
`stageCommandroidBootstrap` task copies the archives into the compat assets under
`bootstrap/`. The system flavor never receives them. Build the archives with
[BOOTSTRAP-BUILD.md](BOOTSTRAP-BUILD.md). **Never pass an official `com.termux`
bootstrap** (or one built for the earlier `com.neoterm` identity): its binaries
hardcode another app's prefix.

Output paths (the debug APK name pattern is set in `app/build.gradle`):

- `app/build/outputs/apk/compat/debug/commandroid-line_compat_debug_{arm64-v8a,armeabi-v7a,x86,x86_64,universal}.apk`
- `app/build/outputs/apk/system/debug/commandroid-line_system_debug_{…}.apk`
- `app/build/outputs/apk/androidTest/compat/debug/app-compat-debug-androidTest.apk`
- `app/build/outputs/apk/androidTest/system/debug/app-system-debug-androidTest.apk`

To get only the universal APK, set `TERMUX_SPLIT_APKS_FOR_DEBUG_BUILDS=0`.
`TERMUX_APP_VERSION_NAME` must be a semver 2.0.0 string, and `validateVersionName`
in `app/build.gradle` checks it. `TERMUX_APK_VERSION_TAG` replaces `debug` in the
file name. These variables keep their upstream `TERMUX_` names.

## Checks

```sh
./gradlew :neoterm-input:test :cmd-translator:test :terminal-emulator:testDebugUnitTest \
          :app:testCompatDebugUnitTest :app:testSystemDebugUnitTest \
          :app:lintCompatDebug :app:lintSystemDebug
adb devices -l
./gradlew :app:connectedCompatDebugAndroidTest :app:connectedSystemDebugAndroidTest
```

`:app:testDebugUnitTest`, `:app:lintDebug` and `:app:assembleDebugAndroidTest`
do not exist now that the app has flavors. [TESTING.md](TESTING.md) records the
current results.

## Target SDK consequences

**system (targetSdk 34).** These upstream changes are in place:

- `TermuxService` and `RunCommandService` declare
  `foregroundServiceType="specialUse"` and start through `ServiceCompat`.
- PendingIntents are `FLAG_IMMUTABLE`.
- The activity receiver is `RECEIVER_NOT_EXPORTED`.
- Notifications are posted only while `POST_NOTIFICATIONS` is granted. The app
  does not request that permission yet, so on API 33+ the service notification
  stays hidden until the user enables it in system settings.
- From API 31, foreground promotion can be refused when a plugin starts
  `RunCommandService` from the background. The services then keep running as
  plain services, and a refused `TermuxService` start is reported as a plugin
  error instead of crashing.
- W^X (targetSdk 29+): SELinux denies `execve()` of files in the app's data
  directory. `RUN_COMMAND` executables and the `~/bin/termux-file-editor` /
  `termux-url-opener` hooks are started through their shebang interpreter, or
  through `/system/bin/sh` when that interpreter is not a system binary. ELF
  binaries in the app's data directory cannot run.
- Shared storage: `WRITE_EXTERNAL_STORAGE` applies only up to API 29. On API 30+,
  `termux-setup-storage` and "save to file" open the "All files access" screen
  (`MANAGE_EXTERNAL_STORAGE`) instead.

**compat (targetSdk 28).** The W^X denial does not apply, so the bootstrap's
binaries in `/data/data/com.commandroid_line/files/usr` can run. This is the
reason the flavor exists. The flavor cannot be published on Google Play, and a
new Android release may warn about or block an old targetSdk at install time.
Lint's `ExpiredTargetSdkVersion` and `OldTargetApi` checks are disabled for this
reason (`app/build.gradle`).

The application ID is separate from the explicit `com.termux` Java/resource
namespace. When changing app identity, keep the source namespace and the native
symbol names intact.

## Signing and installation

Debug builds use Android's locally generated debug keystore, normally
`$HOME/.android/debug.keystore`. The checked-in upstream `app/dev_keystore.jks` is
not used. APKs built with a different key cannot update an installed copy, and
each CI runner generates its own key. Release signing is not configured.

```sh
adb install -r app/build/outputs/apk/compat/debug/commandroid-line_compat_debug_arm64-v8a.apk
adb install -r app/build/outputs/apk/system/debug/commandroid-line_system_debug_arm64-v8a.apk
adb shell am start -n com.commandroid_line/com.termux.app.TermuxActivity
adb shell am start -n com.commandroid_line.system/com.termux.app.TermuxActivity
```

These commands are instructions, not a record that an install was executed.
[DEVICE-TEST-PROTOCOL.md](DEVICE-TEST-PROTOCOL.md) has the device procedure for
the Galaxy S26 Ultra. Never publish or deploy APKs, packages or bootstraps
without the owner's explicit authorization.

## Continuous integration

`.github/workflows/debug_build.yml` runs on pull requests to `main` and pushes to
`main`, with read-only repository permission. For the flavored build it is
expected to run:

- the task list under "Checks" (lint for both flavors, every JVM suite, both
  flavors' debug and androidTest APKs) and the bootstrap tool tests;
- the instrumented suite for both flavors on emulators;
- uploads of reports, logcat and APKs as workflow artifacts.

The flavor-aware workflow ships in its own change. Its results are pending the
first CI run. CI publishes no releases and deploys nothing. The inherited upstream
workflows `attach_debug_apks_to_release.yml`, `run_tests.yml` and
`trigger_library_builds_on_jitpack.yml` were removed from this repository.

## Managed workspace notes

Exporting `ANDROID_HOME` and `JAVA_HOME` is enough. Before downloading
dependencies, check the current environment's network, proxy and CA settings.
Java does not inherit `HTTP_PROXY` automatically, so configure Gradle with the
environment's supported proxy and CA trust, and keep TLS verification enabled.
Proxy values or toolchains from a previous session do not show that the current
session is ready.
