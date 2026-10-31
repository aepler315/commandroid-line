# ADR-0001: One PTY terminal; Commandroid as an input rewrite; one sideload runtime

- Status: accepted (decided by the repository owner; implemented in commit `85bb558`).
  Decision 5 amended on 2026-10-31: the `system` flavor was removed in `b473b51`
  ([Amendment](#amendment-2026-10-31)). The original title ended "two runtime
  flavors".
- Date: 2026-10-31
- Supersedes: the two-stack layout merged by PR #2 (`4a09acb`)

## Context

After PR #2 (`feature/commandroid-spec-rebuild`, merge commit `4a09acb`) the
repository contained two independent terminal architectures in one APK:

1. **Commandroid pipe stack** (`com.aepler.commandroid.*`, second launcher
   `CommandroidActivity`). `ShellTerminalProcessManager` started one
   non-interactive `/system/bin/sh` per session with `ProcessBuilder`, i.e.
   stdin/stdout/stderr were pipes and there was **no PTY**. Each command was
   framed with sentinels written to private fds 8/9 so the shell reported exit
   status and `$PWD`. `SessionEnvironmentProvider` set `TERM=dumb`. Because
   there was no line discipline, Ctrl-C and Ctrl-D were plain bytes on a pipe;
   interrupting a running command went through `killProcess`, which killed the
   descendants and then `destroyForcibly()`-ed the **session shell itself**, losing
   its exported variables and working directory. Programs saw no tty: no termios
   raw mode, no `TIOCGWINSZ`/`SIGWINCH`, no job control. Output was rendered by a
   Canvas `NeoTermView` row buffer without an ANSI/VT parser, driven by an MVI
   `TerminalViewModel`, a foreground `TerminalSessionService` and Hilt.
2. **Upstream PTY stack** (Termux v0.118.3): JNI `TerminalSession` (forkpty-style
   `create_subprocess` in `terminal-emulator/src/main/jni/termux.c`: `setsid`,
   controlling tty, `IUTF8`, `TIOCSWINSZ`), the xterm-compatible
   `TerminalEmulator` (alternate screen, DECSET 2004 bracketed paste, 256
   colour/truecolour) and `TerminalView`, hosted by `TermuxActivity` and
   `TermuxService`, with NeoTerm staged input on top.

The owner's acceptance targets are interactive agent CLIs — Claude Code, Codex
and OpenCode — plus ordinary TUI programs. They require a real tty on
stdin/stdout, raw mode, SIGINT delivered by Ctrl-C to the foreground job (not to
the shell), EOF via Ctrl-D, window size and `SIGWINCH`, an xterm-class `TERM`,
alternate screen, bracketed paste and UTF-8. The pipe stack could provide none
of these; making it do so would have meant re-implementing the PTY stack.

Separately, the app could not run a Termux-style Linux userland at all: from
targetSdk 29, Android's SELinux policy denies `execve()` of files labelled
`app_data_file` (the app's own data directory, W^X). The merged build targeted
SDK 34, so only Android's `/system/bin/sh` and platform binaries could run.

## Decision

1. **One terminal stack.** The upstream JNI PTY `TerminalSession` +
   `TerminalEmulator` + `TerminalView`, hosted by `TermuxActivity`/`TermuxService`,
   is the only way the app runs and displays programs. Interactive CLI programs
   are run **only** in this PTY terminal. No second execution path (pipes,
   `ProcessBuilder` sessions, a separate renderer) may be added without a new ADR.
2. **NeoTerm staged input stays on top** of that terminal
   (`:neoterm-input` `InputEngine`, `NeoTermInputController`,
   `CompositionEditText`). It only writes to the bound `TerminalSession`.
3. **Commandroid is kept as an opt-in Transmit rewrite.** The Windows CMD → POSIX
   translator moved unchanged in behavior to the pure Kotlin module
   `:cmd-translator` (`com.aepler.commandroid.domain`). With the **CMD** checkbox
   on, `CmdTransmitTransform` rewrites the draft at Transmit; the POSIX text is
   written into the shell's line editor and runs only when the user presses
   Enter. Unsupported commands are refused (`SendResult.REFUSED`) and the draft is
   kept. The rewrite never starts a process.
4. **The pipe stack is retired**: `ShellTerminalProcessManager`,
   `TerminalSessionService`, the MVI view model/reducer, Canvas `NeoTermView`,
   `CommandroidActivity` (second launcher), their resources and tests, and
   Hilt/kapt were removed in `85bb558`.
5. **Runtime** (amended 2026-10-31, see [Amendment](#amendment-2026-10-31)).
   The current decision is one app: application ID `com.commandroid_line`,
   **targetSdk 28**, so that binaries in the app's own prefix
   (`/data/data/com.commandroid_line/files/usr`) can `execve` — the same route
   Termux takes. It installs a bootstrap bundled as an APK asset
   (`bootstrap/bootstrap-<arch>.zip`, Gradle `-PcommandroidBootstrapDir=<dir>`)
   that must be built from source for that prefix
   ([BOOTSTRAP-BUILD.md](BOOTSTRAP-BUILD.md)). Without one it falls back to
   `/system/bin/sh` and says so in the runtime notice.

   As decided in `85bb558`, this was one of two runtime flavors (product flavor
   dimension `runtime` in `:app` and `:termux-shared`): `compat` (the runtime
   above) and `system` (application ID `com.commandroid_line.system`, targetSdk
   34, Android `/system/bin/sh` only, the previous NeoTerm runtime). The `system`
   flavor and the flavor dimension were removed in `b473b51`.
6. **Identity** `com.commandroid_line` was chosen by the owner. It is spelled with
   an underscore because hyphens are not legal in Android application IDs
   (`com.commandroid-line` is rejected). The Java/resource namespace and JNI
   symbols stay `com.termux`.
7. **Never an official `com.termux` bootstrap or package.** Those binaries
   hardcode another app's prefix; they are never bundled, downloaded, patched or
   copied in.

## Consequences

- **The app is sideload only.** Google Play requires a current targetSdk; a
  targetSdk 28 APK cannot be published there, and the owner has decided never to
  publish there. Distribution is `adb install` or manual sideloading, and only
  with the owner's explicit authorization.
- **A custom-prefix package build and repository are required.** Every package
  (bash, coreutils, apt, Node.js, git, …) must be compiled for
  `/data/data/com.commandroid_line/files/usr`, and `apt` needs a repository built
  for that prefix. Until a bootstrap exists, the app falls back to Android's
  system shell (no Node/git), and the agent CLIs cannot run.
- **Phantom process killer (Android 12+).** Android may kill "phantom" child
  processes of an app beyond a system-wide limit (default 32) and child processes
  using excessive CPU in the background. Agent CLIs that spawn many processes
  (node workers, git, language servers, shells) are exposed; the shell or CLI can
  die with signal 9. Device setup must disable the restriction or raise the limit
  (see [DEVICE-TEST-PROTOCOL.md](DEVICE-TEST-PROTOCOL.md)).
- **New-device risks.** Devices with a 16 KB memory page size (supported from
  Android 15) need native code with 16 KB-aligned ELF segments; the PTY JNI library
  is built with NDK 22.1.7171670 and the bootstrap with NDK r27c, and 16 KB
  compatibility of either has not been checked. A targetSdk 28 APK can trigger an
  "built for an older version of Android" install warning (Play Protect), and a
  future Android release may raise the minimum installable targetSdk. Both must be
  checked on the owner's Galaxy S26 Ultra; neither has been.
- **Known translator issues are carried over, not fixed.** Examples: `del`,
  `copy`/`xcopy` and `move`/`ren` translations emit `rm`, `cp` and `mv` operands
  without a `--` separator, so a file name beginning with `-` is parsed as an
  option; the glued-`cd` rule (`CD_GLUED` in `DefaultWindowsToLinuxTranslator.kt`)
  treats any word starting with `cd`/`chdir` followed by `.`, `\` or `/`
  (for example a script named `cd.sh`) as a `cd`. The risk is mitigated because a
  translation is only staged in the shell's line editor and nothing runs until the
  user reads it and presses Enter; it is still a defect to fix in
  `:cmd-translator`.
- The CMD rewrite predicts `cd` across lines of a multi-line draft from a snapshot
  of the session's working directory and environment; the shell may have changed
  either since. It is a prediction only. A multi-line draft is therefore
  translated as one `&`-chained command, so the deletion guard tracks every
  directory a preceding `cd` may have reached, and translation is refused when
  the working directory of the session's foreground job is unknown.
- Interactive programs, signals and window size are now the upstream emulator's
  behavior, covered by upstream and new PTY integration tests rather than by a
  bespoke pipe protocol.

## Alternatives considered and rejected

- **Keep both stacks** (pipe stack for CMD use, PTY for everything else): two
  renderers, two session lifecycles, two input paths and two launchers, and the
  pipe path still could not run any interactive program. Rejected.
- **Commandroid UI on a PTY backend** (keep `CommandroidActivity`, MVI and the
  Canvas view, swap the process manager for `TerminalSession`): would require an
  ANSI/VT parser, alternate screen, mouse/bracketed-paste and resize handling in
  the Canvas view — duplicating `TerminalEmulator`/`TerminalView`. Rejected in
  favor of reusing the upstream view and keeping only the translator.
- **Exec through `/system/bin/linker64` on targetSdk 34** (run app-data binaries
  as `linker64 <binary>` to bypass the `execve` denial): fragile and unsupported;
  every child `execve` (shells, `node` spawning `git`, shebang scripts) must be
  wrapped, `/proc/self/exe` and `argv[0]` differ, and it relies on behavior Android
  may close. Rejected; at the time the targetSdk 34 `system` flavor stayed
  system-shell only (that flavor was later removed, see the Amendment).
- **Reuse the `com.termux` identity** (to use official Termux bootstraps and
  repositories): conflicts with an installed Termux, would ship official binaries
  under a different app and signing key, and was not the owner's chosen identity.
  Rejected.

## Amendment (2026-10-31)

The owner decided that commandroid-line will never be published on Google Play.
The `system` flavor (`com.commandroid_line.system`, targetSdk 34,
`/system/bin/sh` only) existed only to have a Play-eligible build, so it was
removed in `b473b51`. Decision 5 above now describes one app.

What changed:

- The `runtime` product flavor dimension is gone from `:app` and
  `:termux-shared`. The former compat sources are in `app/src/main`, and
  `com.commandroid.runtime.FlavorRuntime` became
  `com.commandroid.runtime.AppRuntime` with the same API.
- The application ID is `com.commandroid_line` and targetSdk is 28
  (`targetSdkVersion=28` in `gradle.properties`). The bootstrap asset from
  `-PcommandroidBootstrapDir` is attached to every variant. Without it the app
  uses `com.neoterm.runtime.AndroidShellEnvironment` (`/system/bin/sh`), which
  still exports `COMMANDROID_RUNTIME=system-shell` and
  `NEOTERM_RUNTIME=android-system`.
- Gradle tasks are the plain names (`:app:lintDebug`, `:app:testDebugUnitTest`,
  `:app:assembleDebug`, `:app:assembleDebugAndroidTest`,
  `:app:connectedDebugAndroidTest`), and there is one APK to install.

Consequences:

- No build of commandroid-line meets Google Play's target-API policy, by design.
- There is no longer a targetSdk 34 build to fall back to. If a future Android
  release refuses to install targetSdk 28 APKs, the app stops installing on such
  devices, and no supported replacement route is known (exec through `linker64`
  was rejected above).
- The other decisions (one PTY stack, NeoTerm input, the opt-in CMD rewrite, the
  `com.commandroid_line` identity, no official `com.termux` bootstrap) are
  unchanged.
