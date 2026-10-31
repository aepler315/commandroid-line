# commandroid-line engineering guide

commandroid-line is an independent GPL-3.0-only fork of Termux v0.118.3. It has one
terminal stack (the upstream JNI PTY terminal) with NeoTerm staged input and an
opt-in Windows CMD → POSIX rewrite. Before you change anything, read
`docs/neoterm/ARCHITECTURE.md`, `docs/neoterm/ADR-0001-one-pty-terminal.md`,
`docs/neoterm/BUILD.md` and `docs/neoterm/UPSTREAM.md`.

## Boundaries
- `terminal-emulator/`: upstream terminal parser, screen, `TerminalSession` and JNI PTY.
- `terminal-view/`: upstream `TerminalView`: renderer, gestures, direct keys, InputConnection.
- `termux-shared/`: upstream runtime, file system, shell environment and shared UI.
  It has the `runtime` flavors. `TermuxConstants.TERMUX_PACKAGE_NAME` comes from
  its per-flavor `BuildConfig`, so do not hardcode a package name or prefix.
- `app/src/main/`: upstream activity, service, installer and session management,
  plus NeoTerm input (`java/com/neoterm/input/`) and the system-shell environment
  (`java/com/neoterm/runtime/`).
- `app/src/compat/` and `app/src/system/`: the only flavor-specific code
  (`com.commandroid.runtime.FlavorRuntime`, compat `BootstrapInstaller`) and
  resources. Shared code calls `FlavorRuntime` and never branches on the flavor.
- `neoterm-input/`: pure Kotlin input policy and state machine. No Android, PTY or processes.
- `cmd-translator/`: pure Kotlin Windows CMD → POSIX translator
  (`com.aepler.commandroid.domain`). No Android, PTY or processes. The app uses it
  only through `CmdTransmitTransform`, as an opt-in Transmit rewrite.
- `tools/bootstrap/`: source-bootstrap tooling for the compat prefix (see
  `docs/neoterm/BOOTSTRAP-BUILD.md`).
- `docs/neoterm/`: architecture, ADRs, build, test and device protocols, roadmap.

## Terminal and execution rules
- Interactive programs (shells, editors, TUIs, and the agent CLIs Claude Code,
  Codex and OpenCode) run only in the PTY terminal (`TerminalSession` +
  `TerminalView`). Do not add a second execution path (pipes, `ProcessBuilder`
  sessions, another renderer or launcher) without a new ADR in `docs/neoterm/`.
- The CMD translator never runs a process. Its output goes into the shell's line
  editor and runs only on an explicit Enter. Unsupported commands are refused and
  the draft is kept.
- Never bundle, download, patch or copy official `com.termux` bootstraps or
  packages, or the earlier `com.neoterm` ones. compat bootstraps must be built from
  source for `/data/data/com.commandroid_line/files/usr`.
- Keep upstream changes narrow and marked. Keep the Java, resource and JNI
  namespaces as `com.termux`.

## Input invariants
Never infer terminal ownership of text or cursor from the screen. Android Editable
owns draft text, selection and composition; never mirror them into a second
editable buffer. Only an explicit Transmit writes draft text, without Enter.
Bracketed paste is used when the program enabled it, and it is required for
multi-line or tab text. Enter ↵ writes only `\r`, and only with an empty draft.
IME commit/finish and Enter inside the draft never submit. A session switch clears
the draft. Never repair already-sent input with generated deletes. Preserve direct
key, modifier, escape, native runtime and package-manager behavior. A Transmit
transform's output is validated like typed text.

## Build and verification
Use JDK 17, the pinned Gradle 8.7 wrapper, AGP 8.5.2, Kotlin 1.9.24,
SDK 34/build-tools 34.0.0 and NDK 22.1.7171670. Set `ANDROID_HOME` and
`local.properties` as described in BUILD.md. Every task is per flavor:
- `./gradlew :neoterm-input:test :cmd-translator:test :terminal-emulator:testDebugUnitTest`: pure JVM suites.
- `./gradlew :app:testCompatDebugUnitTest :app:testSystemDebugUnitTest`: app JVM/Robolectric suites.
- `./gradlew :app:lintCompatDebug :app:lintSystemDebug`: lint, with zero errors.
- `./gradlew :app:assembleCompatDebug :app:assembleSystemDebug :app:assembleCompatDebugAndroidTest :app:assembleSystemDebugAndroidTest`: APKs.
- `./gradlew :app:connectedCompatDebugAndroidTest :app:connectedSystemDebugAndroidTest`: instrumented, needs an attached device or emulator.
The old un-flavored names (`:app:testDebugUnitTest`, `:app:lintDebug`) no longer exist.
For input changes, write tests first. Test composition replacement and
cancellation, Unicode, stale callbacks, mode changes, session switches and
transmission/submission separately. For PTY changes, cover interrupts, EOF,
resize, session lifecycle and restarts with instrumented tests.

Record the exact commands, the actual outcomes and any blockers. Never call an
unrun check passed. A compiled androidTest APK is not a passed instrumented run.
Device checks on the owner's Galaxy S26 Ultra (docs/neoterm/DEVICE-TEST-PROTOCOL.md)
and agent CLI acceptance (docs/neoterm/AGENT-CLI-ACCEPTANCE.md) count only when
they are recorded with evidence. Otherwise record them as not run. Real Samsung
Keyboard and Gboard testing is required before any production support claim.

## Standards and restrictions
Use Kotlin for new input logic and Java interop for narrow upstream hooks. UI
operations run on Android's main thread. Prefer Android's standard IME
implementation. Never log input, passwords, key events or text snapshots, and
never log exceptions that may quote input. No draft persistence, analytics or
automatic clipboard copying. User-initiated native editing actions stay
available. Keep all copyright and license notices.

Both feature branches (`feature/neoterm-standalone`,
`feature/commandroid-spec-rebuild`) are merged into `main`. Work on a topic branch
and land it on `main` through a pull request. Pushing topic branches and opening
draft PRs for review is how changes land. Never publish or deploy APKs, packages,
bootstraps or releases without the owner's explicit authorization. Do not
overwrite unrelated work.
