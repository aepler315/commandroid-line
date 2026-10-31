# commandroid-line architecture

commandroid-line is a GPL-3.0-only fork of Termux v0.118.3. It has **one terminal
stack**, the upstream JNI PTY terminal. NeoTerm staged input sits on top of it, and
the Commandroid Windows CMD → POSIX translator is available as an opt-in input
rewrite. [ADR-0001](ADR-0001-one-pty-terminal.md) records why there is one stack
and why there are two runtime flavors.

## Objective and constraints

- Run interactive command-line programs in a real PTY. That includes the agent
  CLIs Claude Code, Codex and OpenCode. The PTY provides signals, raw mode, window
  size, an xterm-class terminal, alternate screen, bracketed paste and UTF-8
  ([AGENT-CLI-ACCEPTANCE.md](AGENT-CLI-ACCEPTANCE.md)).
- Let the user compose and correct a whole prompt with a familiar Android IME
  (Samsung Keyboard, Gboard) before anything reaches the running program. All
  speculative IME text stays outside the PTY.
- Keep Termux's emulator, PTY JNI and direct key path intact, under an
  independent Android identity.

## Module and source-set map

| Location | Role |
| --- | --- |
| `terminal-emulator/` | Upstream. `com.termux.terminal.TerminalSession`: the JNI PTY, created by `create_subprocess` in `src/main/jni/termux.c`. `TerminalEmulator`: the VT/xterm parser, screen, alternate buffer and DECSET 2004. |
| `terminal-view/` | Upstream. `com.termux.view.TerminalView`: renderer, gestures, direct keys and its own `InputConnection`. |
| `termux-shared/` | Upstream runtime, file system, shell environment and shared UI. It carries the `runtime` flavors. `TermuxConstants.TERMUX_PACKAGE_NAME` is `com.termux.shared.BuildConfig.TERMUX_PACKAGE_NAME`, and every prefix and home path derives from it. Identity strings are in `src/compat/res` and `src/system/res`. |
| `app/src/main/java/com/termux/` | Upstream `TermuxActivity`, `TermuxService`, `TermuxInstaller` (startup), the session client and settings. The few commandroid-line hooks call `com.commandroid.runtime.FlavorRuntime`. |
| `app/src/main/java/com/neoterm/input/` | NeoTerm Android input: `NeoTermInputController`, `CompositionEditText`, and `CmdTransmitTransform` (the CMD rewrite). The layout is `app/src/main/res/layout/view_neoterm_input.xml`. |
| `app/src/main/java/com/neoterm/runtime/` | `AndroidShellEnvironment` sets up the `/system/bin/sh` environment: private HOME and TMPDIR, the system PATH, `TERM=xterm-256color`, `COLORTERM=truecolor` and `LANG=C.UTF-8`. |
| `app/src/compat/` | compat flavor: `java/com/commandroid/runtime/FlavorRuntime.java`, `java/com/termux/app/BootstrapInstaller.java`, runtime strings and shortcuts. |
| `app/src/system/` | system flavor: `java/com/commandroid/runtime/FlavorRuntime.java`, label and shortcuts. |
| `neoterm-input/` | Pure Kotlin `com.neoterm.input.InputEngine`: modes, draft lifecycle, the Transmit/Submit policy and `TransmitTransform`. No Android, PTY or process code. |
| `cmd-translator/` | Pure Kotlin `com.aepler.commandroid.domain`, used as `TranslateCommandUseCase(DefaultWindowsToLinuxTranslator())`. No Android, PTY or process code. |
| `tools/bootstrap/` | Prepares and validates the source-built bootstrap for the compat prefix ([BOOTSTRAP-BUILD.md](BOOTSTRAP-BUILD.md)). |

## Runtime flavors

The flavor dimension `runtime` is declared identically in `app/build.gradle` and
`termux-shared/build.gradle`:

| | `compat` | `system` |
| --- | --- | --- |
| Application ID | `com.commandroid_line` | `com.commandroid_line.system` |
| targetSdk | 28 (`compatTargetSdkVersion` in `gradle.properties`) | 34 (`targetSdkVersion`) |
| Default shell | `$PREFIX/bin/login` (bash) once a bootstrap is installed, otherwise `/system/bin/sh` | always `/system/bin/sh` |
| Environment client | `TermuxShellEnvironmentClient` with an installed prefix (`TERM=xterm-256color`, `COLORTERM=truecolor`, `LANG=en_US.UTF-8`), otherwise `AndroidShellEnvironment` | `AndroidShellEnvironment` |
| Linux packages | only from a bootstrap built from source for `/data/data/com.commandroid_line/files/usr` | none |
| Distribution | sideload only (targetSdk 28) | sideload today |

Both flavors use minSdk 26 and compileSdk 34, and they can be installed side by
side. Both keep the `com.termux` Java and resource namespace (`com.termux.R`, and
`com.termux.BuildConfig` with `FLAVOR` and `APPLICATION_ID`). Component classes
such as `com.termux.app.TermuxActivity` keep their upstream names.

### Runtime selection

`com.commandroid.runtime.FlavorRuntime` exists once in each flavor's source set.
It is the only place where the runtimes differ in code:

- `hasInstalledPrefix()`: compat returns true when `$PREFIX/bin/bash` exists.
  system always returns false.
- `shellEnvironment(context)`: `TermuxService` uses it for every session and
  background task.
- `defaultExecutable()`: with a prefix, compat returns `null`, and upstream then
  picks `$PREFIX/bin/login`. Without a prefix it returns `/system/bin/sh`. system
  always returns `/system/bin/sh` and never probes for vendor login, bash or zsh.
- `install(activity, whenDone)`: `TermuxInstaller.setupBootstrapIfNeeded` calls it
  after the primary-user check. compat looks for a bundled
  `bootstrap/bootstrap-<arch>.zip` matching `Build.SUPPORTED_ABIS` and installs the
  first one through `BootstrapInstaller`. That class is upstream's extraction
  logic reading from assets, and it refuses entries or symlinks that would land
  outside the staging prefix. With no asset, compat only prepares the system-shell
  directories. system always just prepares HOME and TMPDIR.
- `runtimeNotice()`: the text shown in `neoterm_runtime_notice`. In compat it
  says whether a prefix is installed (`$PREFIX/bin/bash` exists), not whether the
  APK bundles a bootstrap. The bootstrap installs in the background on first
  launch, and the notice is refreshed when the first session starts, so once the
  install has finished it reads "Termux-compatible runtime".

The `stageCommandroidBootstrap` task in `app/build.gradle` stages the bootstrap
asset from `-PcommandroidBootstrapDir=<dir>`. The directory holds files named
`bootstrap-aarch64.zip`, `bootstrap-arm.zip`, `bootstrap-i686.zip` and
`bootstrap-x86_64.zip`, and the asset is added only to compat variants. Never pass
an official `com.termux` bootstrap: its binaries hardcode another app's prefix.

Startup supports the primary Android user only. For secondary users and work
profiles, `TermuxInstaller` shows an explanatory dialog and stops, because
upstream path constants are primary-user paths.

## Who handles interactive CLI programs

The PTY terminal handles them, always. Every terminal session is a
`TerminalSession` created by `TermuxService`: the first shell, new sessions,
failsafe sessions, and foreground `RUN_COMMAND` sessions. `create_subprocess`
opens a pseudo-terminal and calls `setsid()`, so the PTY becomes the shell's
controlling tty. It also sets `IUTF8` and sets the window size with `TIOCSWINSZ`.
As a result:

- Ctrl-C from `TerminalView` or the extra-keys row writes `0x03` to the PTY. The
  kernel line discipline turns it into SIGINT for the **foreground process
  group**, so the running program is interrupted and the shell survives. Ctrl-D is
  EOF at the line discipline. Ctrl-Z suspends the job when the shell has job
  control.
- A layout change calls `TerminalSession.updateSize`, which resizes the PTY. The
  kernel then sends SIGWINCH to the foreground job, and `TIOCGWINSZ` reports the
  new size.
- Programs can switch termios to raw mode, use the alternate screen and enable
  bracketed paste. `TerminalEmulator` implements all of these.

The only non-PTY execution is upstream's background `RUN_COMMAND` path
(`TermuxTask`, `Runtime.exec`) for plugin commands sent with
`EXTRA_BACKGROUND`. It has no terminal and is not a way to run interactive
programs. No other execution path for user programs exists. Adding one requires a
new ADR.

## Input data flow

```
 hardware key / extra keys ─────────────────────────────┐
 IME in TERMINAL or AUTO (no Compose) → TerminalView ───┤
                                                        ├─► TerminalSession.write ─► PTY ─► foreground program
 IME in TEXT or AUTO+Compose → CompositionEditText      │
        (Android Editable owns the draft)               │
        Transmit → InputEngine.transmit ──[TransmitTransform?]──► payload ┘
        Enter ↵  → InputEngine.submit  ── "\r" only, only with an empty draft
```

- **Modes** (`neoterm_mode_auto`, `neoterm_mode_text`, `neoterm_mode_terminal`).
  AUTO is the default and stays direct until the user taps Compose
  (`neoterm_compose`). TEXT always composes. TERMINAL returns focus to
  `TerminalView` and the upstream key path.
- **Transmit** (`neoterm_transmit`) runs these steps in order:
  1. Finish the local composition.
  2. Validate the draft. It must contain no C0 or C1 controls other than LF and
     TAB, and no malformed surrogates.
  3. Apply the optional transform, then validate the result again.
  4. Write the text once to the bound running session, **without Enter**.

  If the program enabled bracketed paste (DECSET 2004), the payload is wrapped in
  `ESC[200~ … ESC[201~`. Without bracketed paste, a draft containing LF or TAB is
  refused with `BRACKETED_PASTE_REQUIRED`. The draft is cleared only after a
  successful write.
- **Enter ↵** (`neoterm_enter`) writes only `"\r"`, and only when the draft is
  empty. IME commit and finish actions, and Enter inside the draft, never write to
  the terminal.
- **Clear** (`neoterm_clear`) discards the draft locally.
- **Session switch**: `InputEngine.bindSession` clears the draft and invalidates
  stale IME connections, so text composed for one session cannot reach another.
- The draft lives only in memory. It is never saved, autofilled, logged, or
  replayed after the process or activity is recreated.

### CMD rewrite path

The **CMD** checkbox (`neoterm_cmd`) installs a `CmdTransmitTransform` as
`InputEngine.transform` and opens the composer. At Transmit, the draft goes to
`TranslateCommandUseCase`, together with the working directory of the session's
foreground job (`TerminalSession.getCwd()`) and an environment snapshot from
`FlavorRuntime.shellEnvironment`. A multi-line draft is translated as one
`&`-chained CMD command, so the translator's deletion guard tracks every
directory a preceding `cd` may have reached. When the foreground job's working
directory is unknown, the Transmit is refused instead of guessing. The result
decides what happens:

- `Translated`: the draft becomes the POSIX command.
- `BuiltInAction`: the result is `clear`, `exit`, or a `printf` that lists the
  supported subset.
- `UnsupportedCommandError`: the whole Transmit returns `SendResult.REFUSED`.
  Nothing is written and the draft is kept. The reason (`lastRefusal`) is shown in
  `neoterm_draft_status`. An exception inside the transform is also treated as a
  refusal.

The translated text then follows the normal Transmit rules: validation, and
bracketed paste for multi-line text. It lands in the shell's line editor and runs
only when the user presses Enter. Submit and direct keys never go through the
transform. ADR-0001 lists the known translator limitations.

## Ownership and state

Android `Editable` is the only authority for draft text, cursor, selection and
composing spans. `InputEngine` sees the draft through a `Draft` interface and
derives EMPTY, EDITING and COMPOSING from it. DIRECT means the terminal owns
input. A short TRANSMITTING state prevents reentrant sends. All callbacks run on
the main thread. Connection generations reject IME callbacks that arrive after a
mode change, a session change, a new connection or a transmission. NeoTerm never
infers text or cursor ownership from the terminal screen, and it never repairs
already-sent input by generating deletes.

## Privacy

The composer is for non-sensitive text. Use TERMINAL mode for passwords. NeoTerm
never logs draft text, key events or codepoints. Transport and transform
exceptions are not logged either, because they may quote input. The composer
disables view-state saving and autofill, and it requests no personalized
learning. A third-party keyboard's own behavior is still outside the app's
control.

## Boundaries

- `:neoterm-input` and `:cmd-translator` stay pure Kotlin/JVM and are tested on
  the JVM. They must not depend on Android, the PTY or processes.
- Flavor differences live in `app/src/compat`, `app/src/system` and the
  termux-shared flavor resources. Shared code calls `FlavorRuntime` instead of
  checking the flavor.
- Changes to upstream Termux code stay narrow and are marked with NeoTerm or
  commandroid-line comments. Java and JNI namespaces stay `com.termux`.
- User input reaches the PTY only through `TerminalView`'s direct path (keys and
  the upstream paste menu), the extra keys, and `InputEngine` through
  `NeoTermInputController`. Apart from these, only `TerminalEmulator` writes to the
  PTY, to answer terminal queries.
