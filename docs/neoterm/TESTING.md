# Testing commandroid-line

One app is under test: `com.commandroid_line`, targetSdk 28. It runs from a
bundled bootstrap's prefix when one is installed and from `/system/bin/sh`
otherwise. (The targetSdk 34 `system` flavor was removed in `b473b51`; results
recorded for it below are historical.) Record the exact command, the source
revision, the outcome and any
blocker. A check that was not run is recorded as **not run**, never as passed.
A compiled androidTest APK is not a passed instrumented run.

## Commands

```sh
# Pure JVM suites
./gradlew :neoterm-input:test :cmd-translator:test :terminal-emulator:testDebugUnitTest
# App JVM / Robolectric suites (sdk = [28])
./gradlew :app:testDebugUnitTest
# Lint (must report 0 errors)
./gradlew :app:lintDebug
# APKs
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
# Instrumented (needs an attached device or emulator)
./gradlew :app:connectedDebugAndroidTest
# Bootstrap tooling
python3 -m unittest discover -s tools/bootstrap -p 'test_*.py'
```

After installing the app and test APKs yourself, you can also run the
instrumented suite directly:

```sh
adb shell am instrument -w com.commandroid_line.test/androidx.test.runner.AndroidJUnitRunner
```

Reports:

- `neoterm-input/build/reports/tests/test`
- `cmd-translator/build/reports/tests/test`
- `terminal-emulator/build/reports/tests/testDebugUnitTest`
- `app/build/reports/tests/testDebugUnitTest`
- `app/build/reports/lint-results-debug.html`
- `app/build/reports/androidTests/connected/`

## Current results

| Suite | Result at `b473b51` |
| --- | --- |
| `:neoterm-input:test` | 28 tests pass (local, JDK 17) |
| `:cmd-translator:test` | 44 tests pass (local) |
| `:terminal-emulator:testDebugUnitTest` | 145 tests pass (local) |
| `:app:testDebugUnitTest` | 77 tests pass (local) |
| Bootstrap tool tests (`tools/bootstrap/test_*.py`) | 44 tests pass (local) |
| `:app:lintDebug` | 0 errors (local) |
| `:app:assembleDebug`, `:app:assembleDebugAndroidTest` | build (local) |
| `:app:connectedDebugAndroidTest` | **pending** CI emulators (`.github/workflows/debug_build.yml`, API 34 and 36) for this commit; earlier two-flavor results below. Not run locally: the development container has no KVM. |
| Galaxy S26 Ultra ([DEVICE-TEST-PROTOCOL.md](DEVICE-TEST-PROTOCOL.md)) | **not run** |
| Agent CLIs ([AGENT-CLI-ACCEPTANCE.md](AGENT-CLI-ACCEPTANCE.md)) | **not run** |

## Automated layers

**Pure JVM.**

- `InputEngineTest` covers lifecycle, mode policy, transmission bytes, bracketed
  paste, control and malformed-Unicode rejection, reconnect/reset, rapid events,
  failures and the Transmit transform (rewrite, refusal, `lastRefusal`,
  validation of rewritten text).
- `WindowsToLinuxTranslatorTest` covers the CMD → POSIX translations, refusals
  and the deletion guards.
- The upstream terminal-emulator tests cover the parser, screen, cursor and
  scrolling, colors, Unicode input/output, key handling, DECSET 2004 bracketed
  paste and mouse reporting.

**App JVM/Robolectric** (`app/src/test`, plus the shared `app/src/imeTest`).

- `ImeContract` runs against the real Android `Editable`/`InputConnection`.
- `CompositionEditTextTest` and `CompositionLifecycleTest` cover stale context-menu
  callbacks and native batch cleanup after invalidation.
- `InputFocusTest` drives the real activity, composer, Transmit/Enter, resume
  focus and the terminal queue.
- `DirectInputTest` captures the real `TerminalSession` queue without starting
  JNI. It checks command bytes, hardware Delete and Enter, Ctrl-C/D/Z, Alt and
  arrow escapes, password log suppression, stale IMEs and DECSET 2004.
- `StandaloneIdentityTest` reads merged manifest metadata and shortcuts, and
  pins `com.commandroid_line` and the label `commandroid-line`.
- `AndroidShellEnvironmentTest` covers the system-shell environment.
- `StandaloneStartupTest` covers first-run setup and the unsupported-user
  rejection.
- `StandaloneSessionSelectionTest` covers the executable picked for manual and
  failsafe sessions.

These fixtures do not start a Linux process. They are not end-to-end PTY or agent
tests.

**Instrumented** (`app/src/androidTest`, plus `app/src/imeTest`).

- `CompositionInstrumentationTest` runs the shared IME contract on a device.
- `NativePtyCompositionInstrumentationTest` drives the real activity, controller,
  JNI PTY and `/system/bin/sh`, and pins the application ID
  `com.commandroid_line`.

PTY integration tests in `app/src/androidTest/java/com/commandroid/integration`
(shared harness: `PtyTestSupport.kt`) run against the real `TermuxActivity`,
`TermuxService`, JNI PTY and the session's shell:

- `ShellInterruptTest`: Ctrl-C (`0x03`) interrupts a foreground `sleep` and a
  shell loop, SIGINT reaches the foreground child's process group, and Ctrl-D
  ends `cat` with EOF. The shell survives each time.
- `SessionLifecycleTest`: switching sessions clears the draft and input reaches
  only the current session; exiting one session removes it while another keeps
  working.
- `CommandSubmissionTest`: Transmit writes without executing and Enter submits
  exactly once; Enter with a pending draft does not submit; IME commit, editor
  actions and hardware Enter in the draft never submit; CMD mode writes the POSIX
  translation for review and runs it only on Enter.
- `UnicodeInputTest`: precomposed, CJK, supplementary-plane emoji, combining and
  ZWJ sequences arrive byte-exact and are shown by the emulator.
- `ProcessRestartTest`: a SIGKILLed shell ends only its own session, and new
  sessions work repeatedly without leaking finished sessions.
- `InteractiveReadinessTest`: ttys and `TERM`, window size and SIGWINCH, a
  raw-mode arrow key read byte-exact, a bracketed-paste multi-line Transmit read
  raw, the alternate screen, and the bootstrap prefix's bash/node/git. That last
  test is skipped (JUnit assumption) when no bootstrap is installed.

These tests write key bytes with `TerminalSession.write` and resize with
`TerminalSession.updateSize`. They do not inject key events through
`TerminalView` or the extra keys. They call Android's IME interfaces directly and
do not drive a third-party keyboard.

CI emulators: **API 34** (Android 14) and **API 36** (Android 16, the Galaxy
S26 Ultra's release). The CI APK is built without a bootstrap, so the
bootstrap-prefix test is skipped and the Termux-compatible runtime (bash, node,
git) has no instrumented coverage yet.

- **`b473b51` (one app)**: results are **pending** CI.
- **API 34, two-flavor build** (historical, head `4a2c9c8`): every test passed
  in both the compat and the former system flavor.
- **API 36**: added after `4a2c9c8`; no result has been recorded here yet.
- **API 30** (Android 11) ran until `4a2c9c8`, where every test passed. It was
  dropped because no target device runs it. Android 11's toybox `stty` refused
  every option that changes the tty mode (`-icanon`, `-echo`, `-isig`, `-icrnl`).
  The raw-key and bracketed-paste tests therefore still have a canonical-tty
  path for images below API 34. It ends the input line and checks the bytes
  exactly. Interactive CLIs set raw mode themselves through termios, and the
  app's job is byte-exact PTY delivery. From API 34 raw mode is required.

## Behavior matrix

| Behavior | Automated coverage | Device follow-up (DEVICE-TEST-PROTOCOL.md) |
| --- | --- | --- |
| Shell entry, paths, flags | engine exact text; direct queue; environment | K1, K2, H1–H7 |
| Natural-language paragraph, autocorrect, swipe | engine + native draft | K5, K7, K8, K9 |
| Composition updates/cancellation, CJK | native composing region | K14 |
| Multiline and tabs | bracketed-paste requirement | K3, K4, K11, K12 |
| Emoji / combining / RTL | surrogates, codepoint deletion | K13, K15 |
| CMD rewrite and refusal | `InputEngineTest`, translator tests | K16 |
| Escapes and modifiers | rejected in the draft; preserved direct queue | H1–H6 |
| Prompt submission | IME actions do not send; separate `\r` | K6, H7–H9 |
| Ctrl-C / Ctrl-D reach the foreground job | direct queue bytes; PTY integration tests | H2, H3, T1, T2 |
| Resize → SIGWINCH | upstream emulator; PTY integration tests | W1–W4 |
| Session switch / restart | `bindSession`, stale generations | S1–S4, B3, B4 |
| Password privacy | no key logs, no saved/autofill draft | K17, logcat audit |
| Background survival, phantom processes | none | B1–B5 |

## Rules

- Use non-sensitive fixture text. Never record passwords, tokens or API keys, and
  never enable keystroke logging.
- Automated surrogate safety does not promise one-grapheme deletion for every
  emoji sequence. Android and the IME handle grapheme editing, so test vendor
  behavior explicitly.
- Do not mark the agent CLI matrix passed on the strength of an APK without a
  bootstrap (system-shell fallback), JVM queue tests, a CI emulator run or
  archived NeoTerm builds.
- Results from before PR #2 (the `com.neoterm` and `com.termux` builds in
  [STANDALONE-VERIFICATION.md](STANDALONE-VERIFICATION.md) and
  [VERIFICATION.md](VERIFICATION.md)) are historical and say nothing about the
  current APK.
