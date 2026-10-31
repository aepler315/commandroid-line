# commandroid-line changelog

Entries below "One PTY terminal" predate it and describe the earlier NeoTerm
(`com.neoterm`) and PR #2 builds. Their test counts and task names are historical.

## One PTY terminal, compat/system runtimes, documentation (2026-10-31)

- One terminal stack (`85bb558`, [ADR-0001](docs/neoterm/ADR-0001-one-pty-terminal.md)).
  The pipe-based Commandroid stack from PR #2 is retired:
  `ShellTerminalProcessManager` (no PTY, `TERM=dumb`, Ctrl-C destroyed the session
  shell), `TerminalSessionService`, the MVI view model, the Canvas `NeoTermView`,
  the `CommandroidActivity` launcher, and Hilt/kapt. Interactive programs run only
  in the upstream JNI PTY terminal.
- The Windows CMD → POSIX translator is now the pure Kotlin `:cmd-translator`
  module, applied only as an opt-in Transmit rewrite (CMD checkbox,
  `CmdTransmitTransform`). The translation is staged in the shell line and runs
  on Enter. Unsupported commands are refused and the draft is kept.
  `InputEngine` gains `TransmitTransform`, `Rewrite`, `SendResult.REFUSED` and
  `lastRefusal`.
- New `runtime` flavor dimension. compat is `com.commandroid_line`, targetSdk 28,
  with a bundled source-built bootstrap (`-PcommandroidBootstrapDir`) or a
  system-shell fallback. system is `com.commandroid_line.system`, targetSdk 34,
  `/system/bin/sh` only. Gradle tasks are now per flavor (for example
  `:app:testCompatDebugUnitTest`, `:app:lintSystemDebug`).
- Rewrote AGENTS.md, README.md, ARCHITECTURE.md, BUILD.md, TESTING.md,
  REPOSITORY.md, UPSTREAM.md and ROADMAP.md for the merged implementation. Added
  ADR-0001, DEVICE-TEST-PROTOCOL.md (Galaxy S26 Ultra), AGENT-CLI-ACCEPTANCE.md
  and `tools/acceptance/agent-cli-check.sh`, a POSIX sh pre-check run inside the
  app terminal.
- Follow-ups: PTY integration tests in
  `app/src/androidTest/java/com/commandroid/integration` and an emulator CI job
  (API 30 and 34, both flavors). At `e606a3a` API 34 passes; API 30 fails only the
  two raw-mode reads, and their fix (`df69d25`, `stty` options applied one at a
  time) is not yet confirmed by CI. `agent-cli-check.sh` now requires exit 0 and
  a well-formed version for every `--version` check, passes `--interrupt` only
  when the foreground job died of SIGINT, and accepts `COMMANDROID_RUNTIME` as
  well as `NEOTERM_RUNTIME`. Pre-PR #2 documents carry a historical banner.
- Verification: JVM suites pass locally (neoterm-input 28, cmd-translator 44,
  terminal-emulator 145, app 53 per flavor), lint reports 0 errors for both
  flavors, and both flavors' debug and androidTest APKs build (at `85bb558`;
  instrumented CI results are under Follow-ups). The device matrix, the source-built
  bootstrap and the agent CLIs have **not** been run or verified.

## commandroid spec rebuild: SDK 34 toolchain (2026-10-29)

- Build on JDK 17, Gradle 8.7, AGP 8.5.2, compile/target SDK 34, build-tools
  34.0.0 and min SDK 26. CI uses a single Temurin 17 setup and runs
  `:app:lintDebug :app:testDebugUnitTest :app:assembleDebug`, bootstrap tool tests
  and `tools/neoterm-debug.sh`; all debug APKs are uploaded by glob.
- Target SDK 34 fixes in the upstream code: special-use foreground service type for
  `TermuxService`/`RunCommandService`, immutable PendingIntents, non-exported
  activity receiver with package-scoped style broadcasts, and notifications posted
  only with `POST_NOTIFICATIONS`. `:app:lintDebug` reports 0 errors.
- The 220 JVM tests pass on Robolectric 4.12.2 (`sdk = [28]`). Runtime permission
  prompting for notifications and on-device API 34 checks remain pending.

## 0.118.3-neoterm.standalone2 (2026-10-29)

- Handle unmodified physical Enter before the IME in AUTO/direct and TERMINAL.
  Finalize any native direct composing buffer before submitting once, then
  invalidate that connection to reject late replay. TEXT Enter remains local.
- Add pending-composition, keypad-ending and composer-ownership regressions plus
  a live Android hardware-event/shell test. 220 JVM and 21 Android tests pass.
- Provision and verify NDK r27c/JDK17 for the pinned apt bootstrap sources.
  Full package compilation and an apt repository remain pending.

## standalone1 keyboard-focus follow-up (2026-10-29)

- Return typing focus after input action buttons and reselecting an active mode,
  including physical-keyboard Tab/DPAD navigation.
- Added a failing-then-passing focus/letter-editing regression. 217 JVM tests pass;
  app/test APK builds pass. Physical Bluetooth validation remains pending.

## 0.118.3-neoterm.standalone1 — development work (2026-10-29)

- Changed Android application ID to `com.neoterm` and application label to NeoTerm.
- Removed the shared UID declaration; isolated authorities, permissions, actions,
  shortcuts, preferences and private data paths from the upstream package.
- Kept the `com.termux` Java/resource namespace and PTY JNI symbols, with component
  class names independent of the installed application ID.
- Switched debug signing to Android's generated local debug key; release signing
  remains unconfigured. Updates to a development installation require the same key.
- Replaced first-run bootstrap extraction with creation of private home/tmp
  directories and Android system-shell execution. Added a visible runtime notice.
- Removed incompatible upstream bootstrap download/embedding from the app build;
  retained the terminal-emulator PTY library and historical bootstrap C sources.
- Added standalone identity, startup and shell-environment regression tests.
- Fixed unreadable mode/draft labels using an isolated dark input surface, with
  contrast regressions for both activity themes and terminal palettes.
- Verified 216 JVM tests, 20 Android tests (including native PTY composition),
  and installation alongside official Termux with distinct UIDs/data.
- Added source preparation and validation tooling for future custom-prefix
  bootstraps; no completed Linux bootstrap or package repository is included.

This runtime has no apt/pkg, Python, Node, SSH or AI coding CLI support. Current
verification is recorded separately in `docs/neoterm/STANDALONE-VERIFICATION.md`;
device coexistence and real keyboard acceptance require execution evidence.

## 0.118.3-neoterm.m1 — archived development milestone (2026-10-29)

Based on official Termux v0.118.3, commit 5b657c6adf4304e5198951ce815fe0205dcac29c.
Independently developed GPL-3.0-only changes; existing upstream notices preserved.

- Added a pure Kotlin input lifecycle and transmission policy.
- Added native Android paragraph composition with suggestions/autocorrect flags,
  selection replacement, composition cancellation and Unicode deletion guards.
- Added visible AUTO/TEXT/TERMINAL modes; AUTO requires explicit Compose intent.
- Added clearly labeled local draft, Transmit, Clear and separate terminal Enter.
- Preserved upstream direct terminal key/modifier/escape behavior; blocked stale
  direct/composer input connections across mode, session and transmission changes.
- Disabled input-content diagnostic logging; disabled draft state saving/autofill.
- Retained the optional upstream extra keys; added backtick popup to slash and
  removed the older competing send-on-action text toolbar page.
- Fixed stale batch cleanup/context-menu callbacks and resume focus; new drafts
  restore the unsent status after transmission.
- Added pure JVM, Android JVM and shared on-device InputConnection contract tests.
- Pinned Gradle 7.6.4 (with checksum), AGP 7.4.2 and Kotlin 1.8.22 for debug builds.
- Documented architecture, build/test commands, migration, risks and roadmap.

Application identity, bootstrap checksums, Linux prefix, PTY and native runtime
remain upstream-compatible. Physical Gboard/Samsung and agent acceptance is pending;
see docs/neoterm/VERIFICATION.md for actual test and build evidence.
