# Agent CLI acceptance: Claude Code, Codex, OpenCode

**Status: not verified.** No agent CLI has been installed or run in commandroid-line
yet. A source-built compat bootstrap does not exist yet
([BOOTSTRAP-BUILD.md](BOOTSTRAP-BUILD.md)), and no device run has happened
([DEVICE-TEST-PROTOCOL.md](DEVICE-TEST-PROTOCOL.md)). The install commands below are
**expected, to be confirmed**. Nobody has run them in this runtime.

The owner set these three CLIs as central acceptance targets. Each must run
interactively in the **compat** runtime (`com.commandroid_line`), inside the PTY
terminal ([ARCHITECTURE.md](ARCHITECTURE.md)). The system flavor cannot run them:
it has no Node.js, and at targetSdk 34 it cannot execute binaries from its own
data directory.

## Prerequisites

1. A compat APK built with a bootstrap compiled from source for
   `/data/data/com.commandroid_line/files/usr`, passed with
   `-PcommandroidBootstrapDir=<dir>`. The bootstrap must include Node.js
   (`nodejs-lts`, which provides `npm`) and `git` on top of the base set.
   `tools/bootstrap/config.json` (`extra_packages`) is where they are configured,
   and [BOOTSTRAP-BUILD.md](BOOTSTRAP-BUILD.md) covers building it.
   Never install official Termux packages or a `com.termux` bootstrap instead.
2. `$PREFIX/bin/bash` exists, and the runtime notice reads "Termux-compatible
   runtime". On first launch the bootstrap installs in the background, and the
   notice is refreshed when the first terminal session starts, after the
   install. If it still says "without a bundled bootstrap" once a session is
   running, the APK carries no bootstrap for this ABI.
3. Android 12+ phantom-process restrictions are disabled or measured (section 5 of
   the device protocol).
4. Network access, and the user's own account or API key for each CLI. Enter
   credentials only in TERMINAL mode or through the CLI's own login flow. Never
   enter them in the TEXT composer, and never while recording the screen.

## Expected installation (to be confirmed)

These are the packages' published npm names. Installing them in this prefix has
not been tested.

| CLI | Expected install | Binary | Risk to confirm |
| --- | --- | --- | --- |
| Claude Code | `npm install -g @anthropic-ai/claude-code` | `claude` | The documented minimum is Node 18 or newer. Check that it runs on Bionic/Android with the bootstrap's Node. |
| Codex | `npm install -g @openai/codex` | `codex` | The npm package starts a prebuilt native binary. Check which binary its launcher picks: Node reports `process.platform` as `android`, which a launcher may not map to any build. A dynamically linked glibc binary will not run (no glibc loader). A statically linked musl binary generally does run on Android's Linux kernel; the risks there are dynamic linking, DNS (no `/etc/resolv.conf`) and hardcoded `/etc` or `/tmp` paths. |
| OpenCode | `npm install -g opencode-ai` | `opencode` | The npm package starts a prebuilt native binary. Same platform question as Codex. |

Record the actual command used, the package versions, and any postinstall errors.
Treat "a native binary for this platform is missing" as a FAIL with a note, not as
a SKIP. If a different install route turns out to be needed, document it here
after it has been run.

## Automated pre-check

Copy `tools/acceptance/agent-cli-check.sh` into the app's home directory and run
it in a compat terminal session. The app has no access to `/sdcard` until storage
permission is granted, so use one of these routes (expected, to be confirmed on
the device):

- **Download it in the app terminal.** The compat bootstrap includes `curl` (a
  dependency of `termux-tools`). In TERMINAL mode, run
  `curl -fLO https://raw.githubusercontent.com/aepler315/commandroid-line/main/tools/acceptance/agent-cli-check.sh`
  in `~`. Replace `main` with the commit under test.
- **Through shared storage.** `adb push tools/acceptance/agent-cli-check.sh /sdcard/Download/`,
  then run `termux-setup-storage` in the app and allow the storage permission
  prompt. `~/storage/downloads` then points at `/sdcard/Download`, and
  `cp ~/storage/downloads/agent-cli-check.sh ~` copies it.
- **With `run-as`** (debug builds only):
  `adb push tools/acceptance/agent-cli-check.sh /data/local/tmp/` and then
  `adb shell run-as com.commandroid_line cp /data/local/tmp/agent-cli-check.sh files/home/`.

Do not paste the script through the TEXT composer: a multi-line Transmit is
refused unless the receiving program enabled bracketed paste, and `cat > file`
does not. Then run:

```sh
sh agent-cli-check.sh                # non-interactive checks
sh agent-cli-check.sh --interrupt    # press Ctrl-C when asked
sh agent-cli-check.sh --resize       # rotate or resize when asked
```

The script is POSIX sh and non-destructive. It checks that stdin and stdout are a
tty, the window size (`stty size`), ISIG, `intr = ^C` and IUTF8, `TERM`,
`COLORTERM`, a UTF-8 locale, the compat prefix identity, `node` (18 or newer),
`npm` and `git`, and `claude`/`codex`/`opencode --version`. A version check passes
only when the command exits 0 and prints a version: `v<major>.<minor>.<patch>`
for `node`, `<major>.<minor>.<patch>` for `npm`, `git version <x.y.z>` for `git`,
and a free-standing `<x.y.z>` for the agent CLIs. It treats either
`COMMANDROID_RUNTIME=system-shell` or `NEOTERM_RUNTIME=android-system` as the
system-shell fallback. `--interrupt` passes only when the foreground `sleep` was
killed by SIGINT (exit status 130); a SIGINT that reached only the script is a
FAIL. It never prints the
environment, credentials or API keys. It prints one PASS, FAIL or SKIP line per
check, then a summary, and exits with 1 if anything failed. A SKIP for a CLI means
it is not installed, which does not meet acceptance.

Local verification of the script: `sh -n`, `bash -n` and `shellcheck -s sh`
(0.11.0) are clean. It was exercised with dash and bash in Linux
pseudo-terminals, including a real ^C through the line discipline (PASS), a
SIGINT sent only to the script (FAIL, as intended) and a delivered SIGWINCH, and
with stub `node`/`npm`/`git`/CLI commands that print versions, fail with a
Bionic `CANNOT LINK EXECUTABLE` message, or exit 0 without a version. It has
**not** been run on Android, and not under mksh or the bootstrap's own shell.

## Acceptance criteria

Run every row for each CLI in the compat runtime. Use Samsung Keyboard for one
full pass and Gboard for another. Record P/F/S plus notes.

| # | Check | Pass when | Claude Code | Codex | OpenCode |
| --- | --- | --- | --- | --- | --- |
| A1 | `agent-cli-check.sh` | 0 FAIL, and the CLI row is PASS | | | |
| A2 | `<cli> --version` | prints a version and exits 0 | | | |
| A3 | Interactive start in the PTY (`claude`, `codex`, `opencode`) | the TUI draws in full, with no `TERM`/tty warnings, and input is accepted | | | |
| A4 | Ctrl-C while the CLI is running an operation (a long answer or tool call) | the operation is cancelled as documented for that CLI, the CLI stays usable, and after exiting the CLI the same shell is still there (`echo $$` unchanged) | | | |
| A5 | Ctrl-C / Ctrl-D at the CLI's idle prompt | it exits, or asks to confirm, as documented. The shell survives. | | | |
| A6 | Resize: rotate, split screen, DeX | the TUI redraws at the new size with no garbled lines | | | |
| A7 | Multi-line prompt pasted via TEXT + Transmit with bracketed paste on | the whole prompt appears as one input and nothing is submitted until Enter (or the CLI's own submit key) | | | |
| A8 | Multi-line prompt when the CLI has bracketed paste **off** | Transmit is refused and the draft is kept | | | |
| A9 | Unicode in a prompt and in the reply (é, 中文, emoji, combining marks) | correct in both directions, with no width or cursor corruption | | | |
| A10 | Autocorrected prose prompt (TEXT, corrections accepted before Transmit) | the CLI receives only the corrected text, once | | | |
| A11 | A git operation the CLI starts (e.g. `git status`) | it runs; no permission or exec errors | | | |
| A12 | 10 minutes of use, with the app backgrounded once | no process killed (check `adb logcat` for phantom-process kills) | | | |
| A13 | Exit the CLI and run `stty -a` | the terminal is back in cooked mode (`icanon`, `echo`) | | | |

A CLI is accepted only when A1–A13 are all P on the Galaxy S26 Ultra with evidence
recorded ([DEVICE-TEST-PROTOCOL.md](DEVICE-TEST-PROTOCOL.md), section 8).
Emulator-only or JVM-only results do not count.
