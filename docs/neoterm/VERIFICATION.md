# NeoTerm Milestone 1 verification

Verified 2026-10-29. Product source commit: `52fc787834206adfedf2904e66f17267d2831706` on
`feature/neoterm-input-engine`. Subsequent documentation commits do not alter the
APK source. Baseline: stable Termux v0.118.3, commit
`5b657c6adf4304e5198951ce815fe0205dcac29c`.

## Implemented and built

Pure Kotlin lifecycle/paste policy, native Android paragraph editor, visible
AUTO/TEXT/TERMINAL selection, explicit Transmit and separate terminal Enter,
Unicode/deletion guards, session/generation isolation and input-log suppression.
The optional upstream shortcut toolbar remains; its old text-input page is removed.
AUTO uses explicit user intent and otherwise preserves direct input. It does not
classify arbitrary foreground terminal programs.

The input engine owns lifecycle only. Android Editable owns draft text, selection
and composition. IME commit/finish cannot write to the PTY; Transmit never appends
Enter. Multiline/tab transmission requires advertised bracketed paste. Production
identity, Linux prefix, bootstrap checksums, native runtime and PTY are retained.

## Automated tests

`./gradlew :neoterm-input:test testDebugUnitTest --no-daemon --max-workers=2`
passed. The pinned debug script also includes the complete JVM suite.

| Module | Tests | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: |
| neoterm-input | 22 | 0 | 0 | 0 |
| terminal-emulator | 145 | 0 | 0 | 0 |
| app | 33 | 0 | 0 | 0 |
| Total | 200 | 0 | 0 | 0 |

App tests include 19 shared native IME contract tests, 4 native lifecycle regression
tests, 2 activity/queue integration tests, 6 direct terminal queue tests and 2
upstream app tests. The activity test corrects a paragraph via composition calls,
keeps all text local, transmits exact text once with no Enter, rejects a late
callback, marks a new draft unsent, and separately exercises submission.
The queue fixture uses real service/session/emulator objects without spawning JNI
or a Linux process. Programmatic IME API tests are not a real keyboard session.

Independent review found three Important lifecycle/focus defects and one label
issue. All were reproduced before fixes and covered by regression tests; see
[REVIEW.md](REVIEW.md). The author treated the label issue as Important. No review
finding was deferred. Six new regressions/control cases brought the suite from
194 to 200 tests. Before the fixes, five of those six failed as expected.

## Build, lint and artifacts

`JAVA_HOME=/workspace/toolchains/jdk11 ./tools/neoterm-debug.sh` (with the proxy/CA
settings in BUILD.md) passed. Both universal app and instrumentation APKs compiled.
The APK manifest reports com.termux, versionCode 1002, versionName
0.118.3-neoterm.m1, min SDK 24, target SDK 28 and compile SDK 30. Android apksigner
verified the app's v2 signature with one signer (the upstream public debug key).

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| `app/build/outputs/apk/debug/termux-app_neoterm-m1-debug_universal.apk` | 117775167 | `42e9f9304c8a6196b279864b415ed68073a8bea0ffc8a2e74e6aa11846209b5a` |
| `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` | 326628 | `50084695681890e9c5b801046df80105a4fb5c4cdd2fa993eb56fe416f464487` |

Full toolchain: Temurin JDK 11.0.32.1+1, Gradle 7.6.4 with checked distribution
hash, AGP 7.4.2, Kotlin 1.8.22, SDK/build-tools 30/30.0.3, NDK 22.1.7171670.

A second `:app:packageDebug --rerun-tasks` with the same pinned environment
re-executed 105 tasks successfully. `sha256sum --check` confirmed the rebuilt APK
was byte-for-byte identical to the first build. This proves repeatability in this
workspace/toolchain, not reproducibility across arbitrary systems.

`:app:lintDebug` passed: **0 errors, 45 warnings**. Warnings include retained
upstream/dependency findings, the inlined API-26 no-personalized-learning flag
(which is safe to pass as a bit on older versions but may be ignored), and button
styling. The custom native EditText intentionally suppresses AppCompatCustomView
because TermuxActivity is a platform Activity. A preexisting service indentation
error was fixed with whitespace only; execution behavior was preserved.

`git diff --check` and `bash -n tools/neoterm-debug.sh` passed.

## Unexecuted device acceptance and limits

`:app:connectedDebugAndroidTest` was attempted after the fixes and failed only
with **com.android.builder.testing.api.DeviceException: No connected devices!**
`adb devices -l` listed no device; no emulator was configured and /dev/kvm was
absent. The combined lint/connected command exited 1 for this device blocker;
lint itself passed. The instrumentation APK contains the 19 shared input contract
tests, but no successful on-device instrumentation run is claimed.

Not executed: real Gboard/Samsung autocorrect/swipe/candidate conversion, hardware
keyboard/device layout/accessibility acceptance, real PTY input delivery, Claude
Code/Codex CLI/Gemini CLI interactions, device process death/reconnection, or broad
Android API/vendor testing. See [TESTING.md](TESTING.md) for the acceptance matrix.

Use a disposable test device. This APK retains com.termux/shared UID and cannot
install alongside official Termux. It is not production-signed or independently
packaged. Drafts deliberately do not survive session changes/activity recreation.
No termios/password detector or trusted agent-context protocol is implemented.
Large-paste backpressure, receiver acknowledgment, whole-grapheme editing and
third-party IME privacy compliance remain risks in [ROADMAP.md](ROADMAP.md).
The independently installable release plan is in [MIGRATION.md](MIGRATION.md).

No remote push, PR, repository publication or deployment was performed.

## Changed files from stable upstream

- `AGENTS.md`
- `CHANGELOG.md`
- `LICENSES/Apache-2.0.txt`
- `LICENSES/Classpath-exception-2.0.txt`
- `LICENSES/GPL-2.0-only.txt`
- `LICENSES/GPL-3.0-only.txt`
- `LICENSES/MIT.txt`
- `README.md`
- `app/build.gradle`
- `app/src/androidTest/java/com/neoterm/input/CompositionInstrumentationTest.kt`
- `app/src/imeTest/kotlin/com/neoterm/input/ImeContract.kt`
- `app/src/main/java/com/neoterm/input/CompositionEditText.kt`
- `app/src/main/java/com/neoterm/input/NeoTermInputController.kt`
- `app/src/main/java/com/termux/app/TermuxActivity.java`
- `app/src/main/java/com/termux/app/TermuxService.java`
- `app/src/main/java/com/termux/app/terminal/TermuxTerminalSessionClient.java`
- `app/src/main/java/com/termux/app/terminal/TermuxTerminalViewClient.java`
- `app/src/main/java/com/termux/app/terminal/io/TerminalToolbarViewPager.java`
- `app/src/main/res/layout/activity_termux.xml`
- `app/src/main/res/layout/view_neoterm_input.xml`
- `app/src/main/res/values/neoterm_strings.xml`
- `app/src/test/java/com/neoterm/input/CompositionEditTextTest.kt`
- `app/src/test/java/com/neoterm/input/CompositionLifecycleTest.kt`
- `app/src/test/java/com/neoterm/input/InputFocusTest.kt`
- `app/src/test/java/com/termux/terminal/DirectInputTest.java`
- `app/src/test/java/com/termux/terminal/SessionFixture.java`
- `build.gradle`
- `docs/neoterm/APK-SHA256SUMS`
- `docs/neoterm/ARCHITECTURE.md`
- `docs/neoterm/BUILD.md`
- `docs/neoterm/MIGRATION.md`
- `docs/neoterm/PROGRESS.md`
- `docs/neoterm/REVIEW.md`
- `docs/neoterm/ROADMAP.md`
- `docs/neoterm/TESTING.md`
- `docs/neoterm/UPSTREAM.md`
- `docs/neoterm/VERIFICATION.md`
- `docs/superpowers/plans/2026-10-29-neoterm-input.md`
- `gradle/wrapper/gradle-wrapper.properties`
- `neoterm-input/build.gradle`
- `neoterm-input/src/main/kotlin/com/neoterm/input/InputEngine.kt`
- `neoterm-input/src/test/kotlin/com/neoterm/input/InputEngineTest.kt`
- `settings.gradle`
- `terminal-emulator/src/main/java/com/termux/terminal/TerminalEmulator.java`
- `terminal-view/src/main/java/com/termux/view/TerminalView.java`
- `termux-shared/src/main/java/com/termux/shared/settings/properties/TermuxPropertyConstants.java`
- `tools/neoterm-debug.sh`
