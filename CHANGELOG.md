# commandroid-line changelog

Entries below "One PTY terminal" predate it and describe the earlier NeoTerm
(`com.neoterm`) and PR #2 builds. Their test counts and task names are historical.

## Bootstrap identity in termux-tools; verify-artifact enforced (2026-10-31)

- Bootstrap run 41215503863 built both architectures, but the archives are not
  usable. termux-tools' `configure` falls back to `com.termux` paths unless
  `TERMUX_APP_PACKAGE`, `TERMUX_PREFIX` and the related variables are exported.
  So `login`, `pkg`, `chsh`, `motd.sh` and 12 other runtime files named
  `/data/data/com.termux/files/usr`. `bootstrap.py` now exports them in
  termux-tools' `termux_step_pre_configure`. It also points the four app
  component names at `com.termux.app.*` (the Java namespace), not
  `com.commandroid_line.app.*`.
- The build job's verify step ran `verify-artifact | tee` without `pipefail`, so
  its failure was masked and the job reported success. That step now uses
  `set -euo pipefail`, and `bootstrap-apk.yml` caught the failure.
- `verify-artifact` accepts an extra executable installed as a symlink (git's
  `bin/git`). It allows one exact upstream comment in
  `bin/termux-exec-ld-preload-lib`, and rejects app components in the
  application-ID namespace. Both archives from that run pass every check once
  termux-tools' own files are corrected, so a rebuild is expected to pass.

## One sideload app; system flavor removed (2026-10-31)

- The owner decided commandroid-line will never be published on Google Play, so
  the Play-eligible `system` flavor (`com.commandroid_line.system`, targetSdk 34,
  `/system/bin/sh` only) was removed in `b473b51`. The `runtime` flavor dimension
  is gone from `:app` and `:termux-shared`. The entry below describes the
  two-flavor layout as it was; it is no longer current.
- commandroid-line is one app: `com.commandroid_line`, targetSdk 28
  (`targetSdkVersion=28` in `gradle.properties`; `compatTargetSdkVersion` is
  removed), sideload only. It runs Termux-compatible binaries from its own prefix
  `/data/data/com.commandroid_line/files/usr` when a source-built bootstrap is
  bundled with `-PcommandroidBootstrapDir`, and falls back to Android's
  `/system/bin/sh` (`com.neoterm.runtime.AndroidShellEnvironment`, which still
  exports `COMMANDROID_RUNTIME=system-shell` and `NEOTERM_RUNTIME=android-system`)
  when none is bundled.
- `com.commandroid.runtime.FlavorRuntime` is now `com.commandroid.runtime.AppRuntime`
  (same API), and the former compat sources live in `app/src/main`.
- Gradle tasks are the plain names again: `:app:lintDebug`, `:app:testDebugUnitTest`,
  `:app:assembleDebug`, `:app:assembleDebugAndroidTest` and
  `:app:connectedDebugAndroidTest`, plus `:neoterm-input:test`,
  `:cmd-translator:test` and `:terminal-emulator:testDebugUnitTest`. CI uploads
  the app APK alone as `commandroid-line-debug-<sha>`
  (`commandroid-line_debug_universal.apk`) and the instrumentation APK as
  `commandroid-line-androidTest-<sha>`. CI emulators are API 34 and 36.
- Documentation updated for the single app; ADR-0001 has a dated amendment.
- Verification at `b473b51`: lint passes, and locally app JVM 77, cmd-translator
  44, neoterm-input 28, terminal-emulator 145 and bootstrap tool 44 tests pass.
  On CI emulators, API 36 first failed
  `CommandSubmissionTest.imeCommitEditorActionsAndHardwareEnterNeverSubmit`; it also failed on
  `main` before this change. The `aaf200c` diagnostics showed Gboard consuming the hardware
  Enter before the draft editor's `onKeyDown`, with nothing reaching the PTY.
  `59f17d0` takes hardware Enter in `onKeyPreIme` and leaves it to the IME during a
  composition; from there every test passes on API 34 and API 36. No device testing has
  been done, the agent CLIs are unverified, and no bootstrap has been built
  successfully yet (bootstrap CI run #6 was in progress).

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
  (API 34 and 36, both flavors; API 30 ran until `4a2c9c8`, where it passed,
  and was then dropped because no target device runs Android 11). Android 11's
  toybox `stty` cannot change the tty mode, so below API 34 the raw-mode tests
  check byte-exact PTY delivery through a canonical tty. From API 34 raw mode is
  required. `agent-cli-check.sh` now requires exit 0 and
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
