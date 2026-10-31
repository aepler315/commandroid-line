# Building commandroid-line

commandroid-line is one app: `com.commandroid_line`, targetSdk 28,
Termux-compatible execution from the app's own prefix when a source-built
bootstrap is bundled, and Android's `/system/bin/sh` otherwise. It is sideload
only and will never be published on Google Play. There are no product flavors,
so the Gradle tasks have the plain `Debug` names.
[ADR-0001](ADR-0001-one-pty-terminal.md) explains the choice, and its amendment
of 2026-10-31 records why the former targetSdk 34 `system` build was removed.

## Pinned build inputs

- Upstream base: stable Termux v0.118.3 (`5b657c6adf4304e5198951ce815fe0205dcac29c`).
- JDK 17 (Temurin 17 in CI). Java and Kotlin JVM target 17.
- Gradle 8.7 wrapper, with the distribution SHA-256 checked in
  `gradle/wrapper/gradle-wrapper.properties`.
- Android Gradle Plugin 8.5.2, Kotlin 1.9.24. No kapt or Hilt.
- Android SDK platform 34, build-tools 34.0.0, and NDK 22.1.7171670 for the PTY
  JNI library (`ndkVersion` in `gradle.properties`).
- minSdk 26, compileSdk 34 and targetSdk 28 (`minSdkVersion`,
  `compileSdkVersion` and `targetSdkVersion` in `gradle.properties`).
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

## Building

```sh
# without a bootstrap: installs, shows a notice, uses /system/bin/sh
./gradlew :app:assembleDebug

# with a bootstrap built from source for com.commandroid_line
./gradlew :app:assembleDebug -PcommandroidBootstrapDir=/path/to/bootstraps

# instrumentation APK
./gradlew :app:assembleDebugAndroidTest
```

The `-PcommandroidBootstrapDir` directory must contain at least one of
`bootstrap-aarch64.zip`, `bootstrap-arm.zip`, `bootstrap-i686.zip` or
`bootstrap-x86_64.zip`. If it contains none, the build fails. The
`stageCommandroidBootstrap` task copies the archives into the app's assets under
`bootstrap/`. Build the archives with
[BOOTSTRAP-BUILD.md](BOOTSTRAP-BUILD.md). **Never pass an official `com.termux`
bootstrap** (or one built for the earlier `com.neoterm` identity): its binaries
hardcode another app's prefix.

Output paths (the debug APK name pattern is set in `app/build.gradle`):

- `app/build/outputs/apk/debug/commandroid-line_debug_{arm64-v8a,armeabi-v7a,x86,x86_64,universal}.apk`
- `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`

To get only the universal APK, set `TERMUX_SPLIT_APKS_FOR_DEBUG_BUILDS=0`.
`TERMUX_APP_VERSION_NAME` must be a semver 2.0.0 string, and `validateVersionName`
in `app/build.gradle` checks it. `TERMUX_APK_VERSION_TAG` replaces `debug` in the
file name. These variables keep their upstream `TERMUX_` names.

## Checks

```sh
./gradlew :neoterm-input:test :cmd-translator:test :terminal-emulator:testDebugUnitTest \
          :app:testDebugUnitTest :app:lintDebug
adb devices -l
./gradlew :app:connectedDebugAndroidTest
```

`tools/commandroid-debug.sh` runs the lint, JVM and APK tasks of the CI "build"
job in one Gradle call. [TESTING.md](TESTING.md) records the current results.

## Target SDK consequences

**targetSdk 28.** The W^X denial (SELinux refusing `execve()` of files in the
app's data directory, from targetSdk 29) does not apply, so the bootstrap's
binaries in `/data/data/com.commandroid_line/files/usr` can run. This is why the
app targets SDK 28. Such an APK cannot be published on Google Play, which the
owner does not intend to do, and a new Android release may warn about or block
an old targetSdk at install time. Lint's `ExpiredTargetSdkVersion` and
`OldTargetApi` checks are disabled for this reason (`app/build.gradle`).

**Changes kept from the earlier targetSdk 34 build.** The PR #2 work and the
former `system` flavor targeted SDK 34. These changes to the upstream code from
that work are still in place. How Android applies them to the targetSdk 28 app has not
been checked on a device:

- `TermuxService` and `RunCommandService` declare
  `foregroundServiceType="specialUse"` and start through `ServiceCompat`.
- PendingIntents are `FLAG_IMMUTABLE`.
- The activity receiver is `RECEIVER_NOT_EXPORTED`.
- Notifications are posted only while `POST_NOTIFICATIONS` is granted. The app
  does not request that permission itself. When Android 13+ asks a targetSdk 28
  app for it, and whether the service notification is shown before then, has
  not been checked.
- From API 31, foreground promotion can be refused when a plugin starts
  `RunCommandService` from the background. The services then keep running as
  plain services, and a refused `TermuxService` start is reported as a plugin
  error instead of crashing.
- Without a bootstrap prefix, `AndroidShellEnvironment` starts `RUN_COMMAND`
  executables and the `~/bin/termux-file-editor` / `termux-url-opener` hooks
  through their shebang interpreter, or through `/system/bin/sh` when that
  interpreter is not a system binary. This was written for the W^X denial at
  targetSdk 29+.
- Shared storage: `WRITE_EXTERNAL_STORAGE` applies only up to API 29. On API 30+,
  `termux-setup-storage` and "save to file" open the "All files access" screen
  (`MANAGE_EXTERNAL_STORAGE`) instead.

The application ID is separate from the explicit `com.termux` Java/resource
namespace. When changing app identity, keep the source namespace and the native
symbol names intact.

## Signing and installation

Debug builds use Android's locally generated debug keystore, normally
`$HOME/.android/debug.keystore`. The checked-in upstream `app/dev_keystore.jks` is
not used. APKs built with a different key cannot update an installed copy, and
each CI runner generates its own key. Release signing is not configured.

```sh
adb install -r app/build/outputs/apk/debug/commandroid-line_debug_arm64-v8a.apk
adb shell am start -n com.commandroid_line/com.termux.app.TermuxActivity
```

With `TERMUX_SPLIT_APKS_FOR_DEBUG_BUILDS=0` (as in CI), install
`commandroid-line_debug_universal.apk` instead.

These commands are instructions, not a record that an install was executed.
[DEVICE-TEST-PROTOCOL.md](DEVICE-TEST-PROTOCOL.md) has the device procedure for
the Galaxy S26 Ultra. Never publish or deploy APKs, packages or bootstraps
without the owner's explicit authorization.

## Continuous integration

`.github/workflows/debug_build.yml` runs on pull requests to `main`, pushes to
`main` and manual `workflow_dispatch`, with read-only repository permission. It runs:

- a "build" job: the bootstrap tool tests (`tools/bootstrap/test_*.py`), then
  `:app:lintDebug`, `:neoterm-input:test`, `:cmd-translator:test`,
  `:terminal-emulator:testDebugUnitTest`, `:app:testDebugUnitTest`,
  `:app:assembleDebug` and `:app:assembleDebugAndroidTest`, the same list as
  `tools/commandroid-debug.sh`;
- an "instrumented" job: `:app:connectedDebugAndroidTest` on emulators at API 34
  and API 36.

It uploads these workflow artifacts: `commandroid-line-debug-<sha>` (only
`commandroid-line_debug_universal.apk`, because CI sets
`TERMUX_SPLIT_APKS_FOR_DEBUG_BUILDS=0`), `commandroid-line-androidTest-<sha>`
(the instrumentation APK), `commandroid-reports-<sha>`, and per API level
`commandroid-androidTest-api<level>-<sha>` and `commandroid-logcat-api<level>-<sha>`.
Instrumented results: every test passes on API 34 and API 36 from `59f17d0` (see TESTING.md). CI does not bundle a
bootstrap, so its APK uses the `/system/bin/sh` fallback. CI publishes no
releases and deploys nothing. The inherited upstream
workflows `attach_debug_apks_to_release.yml`, `run_tests.yml` and
`trigger_library_builds_on_jitpack.yml` were removed from this repository.

## Managed workspace notes

Exporting `ANDROID_HOME` and `JAVA_HOME` is enough. Before downloading
dependencies, check the current environment's network, proxy and CA settings.
Java does not inherit `HTTP_PROXY` automatically, so configure Gradle with the
environment's supported proxy and CA trust, and keep TLS verification enabled.
Proxy values or toolchains from a previous session do not show that the current
session is ready.
