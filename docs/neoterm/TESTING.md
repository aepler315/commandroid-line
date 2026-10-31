# Testing commandroid-line

Two runtime flavors are under test: `compat` (`com.commandroid_line`, targetSdk 28)
and `system` (`com.commandroid_line.system`, targetSdk 34). Every Android task is
per flavor. Record the exact command, the source revision, the outcome and any
blocker. A check that was not run is recorded as **not run**, never as passed.
A compiled androidTest APK is not a passed instrumented run.

## Commands

```sh
# Pure JVM suites
./gradlew :neoterm-input:test :cmd-translator:test :terminal-emulator:testDebugUnitTest
# App JVM / Robolectric suites (sdk = [28])
./gradlew :app:testCompatDebugUnitTest :app:testSystemDebugUnitTest
# Lint (must report 0 errors)
./gradlew :app:lintCompatDebug :app:lintSystemDebug
# APKs
./gradlew :app:assembleCompatDebug :app:assembleSystemDebug \
          :app:assembleCompatDebugAndroidTest :app:assembleSystemDebugAndroidTest
# Instrumented (needs an attached device or emulator)
./gradlew :app:connectedCompatDebugAndroidTest :app:connectedSystemDebugAndroidTest
```

After installing the app and test APKs yourself, you can also run the
instrumented suite directly:

```sh
adb shell am instrument -w com.commandroid_line.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w com.commandroid_line.system.test/androidx.test.runner.AndroidJUnitRunner
```

Reports:

- `neoterm-input/build/reports/tests/test`
- `cmd-translator/build/reports/tests/test`
- `terminal-emulator/build/reports/tests/testDebugUnitTest`
- `app/build/reports/tests/test{Compat,System}DebugUnitTest`
- `app/build/reports/lint-results-{compat,system}Debug.html`
- `app/build/reports/androidTests/connected/`

## Current results

| Suite | Result at `85bb558` |
| --- | --- |
| `:neoterm-input:test` | 28 tests pass (local, JDK 17) |
| `:cmd-translator:test` | 44 tests pass (local) |
| `:terminal-emulator:testDebugUnitTest` | 145 tests pass (local) |
| `:app:testCompatDebugUnitTest` / `:app:testSystemDebugUnitTest` | 53 tests pass in each (local) |
| `:app:lintCompatDebug` / `:app:lintSystemDebug` | 0 errors (local) |
| Debug and androidTest APKs, both flavors | build (local) |
| `:app:connectedCompatDebugAndroidTest` / `:app:connectedSystemDebugAndroidTest` | **pending first CI run** on emulators. Not run locally: the development container has no KVM. |
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
- `StandaloneIdentityTest` reads merged manifest metadata and shortcuts per
  flavor.
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
  JNI PTY and `/system/bin/sh`.

The suite must also contain PTY integration tests for shell interrupts (Ctrl-C
reaches the foreground job and the shell survives), session lifecycle, command
submission (Transmit without Enter, Enter alone), Unicode input and process
restarts. These are being added alongside this documentation change. Their
results are pending the first CI run. The instrumented tests call Android's IME
interfaces directly and do not drive a third-party keyboard.

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
- Do not mark the agent CLI matrix passed on the strength of a system-shell APK,
  JVM queue tests, a CI emulator run or archived NeoTerm builds.
- Results from before PR #2 (the `com.neoterm` and `com.termux` builds in
  [STANDALONE-VERIFICATION.md](STANDALONE-VERIFICATION.md) and
  [VERIFICATION.md](VERIFICATION.md)) are historical and say nothing about the
  current APKs.
