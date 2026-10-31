# Device test protocol: Samsung Galaxy S26 Ultra

**Status: none of this protocol has been run yet.** No device testing has been
done. No device or emulator was reachable from the development sessions that
produced commits `85bb558` through `b473b51`. Every
result row below is blank until someone runs it on the phone and records the
evidence. A compiled test APK, a passing JVM suite or a CI emulator run does not
fill in any row of the manual matrix.

This protocol covers the one commandroid-line app, `com.commandroid_line`
(targetSdk 28, sideload only), with or without a bundled bootstrap. For the
agent CLI checks, see also
[AGENT-CLI-ACCEPTANCE.md](AGENT-CLI-ACCEPTANCE.md). The design under test is
described in [ARCHITECTURE.md](ARCHITECTURE.md).

## 1. Record the device

Before testing, fill in this header. Copy it into the results file (section 9).

| Field | Value |
| --- | --- |
| Model / CSC (Settings › About phone) | Galaxy S26 Ultra / |
| Android version, One UI version, build number | |
| Security patch level | |
| Memory page size (`adb shell getconf PAGE_SIZE`) | |
| Primary ABI (`adb shell getprop ro.product.cpu.abi`) | |
| App commit (`git rev-parse HEAD`) | |
| Bootstrap bundled? (path / sha256 of `bootstrap-aarch64.zip`, or "none") | |
| Samsung Keyboard version and language(s) | |
| Gboard version and language(s) | |
| Hardware keyboard (model, Bluetooth / USB / DeX) | |
| Tester, date | |

If the page size is 16384, record it prominently. 16 KB compatibility of the PTY
JNI library and of the bootstrap binaries has not been checked
([ADR-0001](ADR-0001-one-pty-terminal.md)).

## 2. Enable USB debugging and connect

1. Settings › About phone › Software information: tap **Build number** seven times
   to enable Developer options.
2. Settings › Developer options: turn on **USB debugging**. **Wireless
   debugging** also works with `adb pair` / `adb connect`.
3. Connect the phone and accept the RSA fingerprint prompt.
4. On the computer, run `adb devices -l`. The phone must show as `device`, not
   `unauthorized`.

This works from Windows as well. Install the Android SDK platform-tools, run
`adb` from PowerShell or cmd, and use `gradlew.bat` instead of `./gradlew`.

## 3. Build and install the APK

Build on the computer ([BUILD.md](BUILD.md)):

```sh
./gradlew :app:assembleDebug
# For Termux-compatible execution, add -PcommandroidBootstrapDir=<dir>
# (a source-built bootstrap only; see BOOTSTRAP-BUILD.md).
```

Install the one APK:

```sh
adb install -r app/build/outputs/apk/debug/commandroid-line_debug_arm64-v8a.apk
adb shell pm list packages | grep commandroid_line
adb shell dumpsys package com.commandroid_line | grep -E 'targetSdk|versionName'
```

With `TERMUX_SPLIT_APKS_FOR_DEBUG_BUILDS=0` only
`commandroid-line_debug_universal.apk` is produced. Install that instead. It is
also the only APK in the CI artifact `commandroid-line-debug-<sha>`.

If an older two-flavor build left `com.commandroid_line.system` installed, it is
no longer built or tested; record whether it is present and uninstall it
(`adb uninstall com.commandroid_line.system`) only if it holds no data you need.

Record these install observations:

- Any install-time warning, such as Play Protect's "built for an older version of
  Android" (the app targets SDK 28).
- `INSTALL_FAILED_*` errors. `INSTALL_FAILED_UPDATE_INCOMPATIBLE` means the
  installed copy was signed with another debug key. Do not uninstall an app that
  holds data you need just to get past it.
- Whether the "commandroid-line" launcher appears, and only once.

## 4. Instrumented suite on the phone

Run the same instrumented suite that CI runs on emulators, with the phone
attached:

```sh
./gradlew :app:connectedDebugAndroidTest
```

Reports are written to `app/build/reports/androidTests/connected/` and results to
`app/build/outputs/androidTest-results/`. Record the pass, fail and skip counts.
Keep the HTML report as evidence (section 8).

The Gradle connected tasks install their own app and test APKs. By default they
uninstall them again after the run, which also deletes that app's data (an
installed bootstrap, HOME). Run this step **before** the manual matrix, or
reinstall afterwards. The instrumented tests drive Android's IME interfaces
directly. They do not exercise Samsung Keyboard or Gboard, so the manual matrix
is still required.

## 5. Phantom process and background settings (Android 12+)

Android 12 and later can kill an app's child processes. The limit is a
system-wide count of phantom processes (default 32), and processes that use too
much CPU in the background are also killed. Agent CLIs spawn node workers, git,
shells and language servers, so they are likely to be affected.
**Needed:** treat this as required for the agent CLI rows. Run them once with the
default setting to learn whether it matters on this phone, then again after
changing it, and record both.

Apply either of these:

- Developer options › **Disable child process restrictions**. This appears on
  Android 14+ and some earlier builds. Record whether the phone shows it.
- Or use adb (the commands Termux documents for Android 12L+):

  ```sh
  adb shell "/system/bin/device_config set_sync_disabled_for_tests persistent"
  adb shell "/system/bin/device_config put activity_manager max_phantom_processes 2147483647"
  adb shell settings put global settings_enable_monitor_phantom_procs false
  ```

To verify the change:

```sh
adb shell /system/bin/device_config get activity_manager max_phantom_processes
adb shell settings get global settings_enable_monitor_phantom_procs
adb shell "/system/bin/dumpsys activity settings | grep -i phantom"
```

Functional check: in the app's terminal, run
`for i in $(seq 40); do sleep 900 & done`. Leave the app in the background for 5
minutes, return, and run `jobs | wc -l`. The count should still be 40. Also run
`adb logcat -d -b main,system | grep -i phantom` and record any kill lines.

One UI background settings: open Settings › Apps › commandroid-line › Battery and
set **Unrestricted**. Also make sure the app is not in Settings › Battery ›
Background usage limits › **Sleeping apps** / **Deep sleeping apps**; add it to
**Never sleeping apps**. Menu names vary between One UI versions, so record the
path you actually used. Test once with the defaults first (section 6.4, rows B1–B3).

## 6. Manual input matrix

Use non-sensitive fixture text only. Never type real passwords, tokens or API keys
while recording (section 8). Two shell fixtures are useful. To capture one line,
run `read -r line; printf '[%s]\n' "$line"`. To show exact bytes, run
`cat -v` and finish with Ctrl-D. Turn bracketed paste on or off in bash with
`bind 'set enable-bracketed-paste on'` or `off`. The system shell (mksh) does
not enable it.

For each IME, run every row in **TEXT** mode unless the row says otherwise. Fill
in **P** (pass), **F** (fail, with a note), **S** (skipped, with the reason) or
**N/A**.

### 6.1 Software keyboards (run once with Samsung Keyboard, once with Gboard)

| # | Case | Expected | Samsung Keyboard | Gboard | Notes |
| --- | --- | --- | --- | --- | --- |
| K1 | Single-line command `ls -la /sdcard` in TEXT, Transmit, then Enter ↵ | Nothing is sent while typing. Transmit puts the text on the prompt with no newline. Enter runs it once. | | | |
| K2 | Same in AUTO after tapping Compose | Same as K1. Without Compose, keys go direct. | | | |
| K3 | Multiline composition, two lines, bracketed paste **on** (bash) | Transmit sends both lines as one bracketed paste. Nothing runs until Enter. | | | |
| K4 | Multiline composition, bracketed paste **off** (or `/system/bin/sh`) | Transmit is refused with the bracketed-paste message and the draft is kept. | | | |
| K5 | Autocorrect / predictive replacement of a word before Transmit (type `teh` and accept the correction; tap a suggestion to replace a word) | Only the corrected text is sent, once. No stray deletes or duplicates. | | | |
| K6 | IME Enter / Done / Send inside the draft | A newline is inserted or input finishes locally. Nothing is written to the terminal. | | | |
| K7 | Swipe / glide typing of a sentence | The draft holds the sentence. Nothing is sent before Transmit. | | | |
| K8 | Voice input (optional) | Same as K7. | | | |
| K9 | Handwriting (optional; Samsung S Pen / Gboard handwriting) | Same as K7. | | | |
| K10 | Paste a single-line command from the clipboard into the draft | Exact text. Transmit and Enter run it once. | | | |
| K11 | Paste a multi-line command block | Same rule as K3 and K4, depending on bracketed paste. | | | |
| K12 | Paste text containing tabs | Refused without bracketed paste. Tabs preserved with it. | | | |
| K13 | Emoji (including ZWJ sequences such as 👩‍💻) | Correct bytes reach the program (`cat -v` shows UTF-8). Backspace in the draft never leaves half a surrogate. | | | |
| K14 | CJK input with candidate conversion (Japanese/Chinese/Korean) | Only the converted text is sent. Composition never leaks. | | | |
| K15 | Combining marks (e.g. `e` + U+0301) and RTL text | Exact codepoints reach the program. | | | |
| K16 | CMD checkbox: `dir /b`, then `del old.txt`, then an unsupported command such as `robocopy a b` | The POSIX translation appears on the prompt and does not run until Enter. The unsupported command is refused with a reason and the draft is kept. | | | |
| K17 | TERMINAL mode: type at a `read -rs` password prompt | No suggestions or learning strip is shown. No input appears in logcat (section 8). | | | |

### 6.2 Hardware keyboard (Bluetooth, USB or Samsung DeX)

| # | Case | Expected | Result | Notes |
| --- | --- | --- | --- | --- |
| H1 | Arrow keys at the shell prompt and in `vi`/`less` | Cursor and history move. Correct escape sequences reach the program. | | |
| H2 | Ctrl-C while `sleep 100` runs | `sleep` is interrupted. The shell prompt returns and the same shell keeps running (`echo $$` unchanged). | | |
| H3 | Ctrl-D at an empty prompt in a nested `sh` | The nested shell exits. The outer shell survives. | | |
| H4 | Esc in `vi` | Leaves insert mode. | | |
| H5 | Tab completion in bash (bootstrap installed) | Completes. | | |
| H6 | Alt combinations (Alt-b / Alt-f in bash) | Word movement works. | | |
| H7 | Enter in TERMINAL and in AUTO (direct) | Submits once. | | |
| H8 | Enter in TEXT with a non-empty draft | Inserts a newline locally. Nothing is sent. | | |
| H9 | Enter ↵ button with a non-empty draft | Refused ("Transmit or clear the draft"). | | |
| H10 | Keyboard layout switch (e.g. Shift-Space) while composing | No text is sent. The draft is intact. | | |

### 6.3 Sessions, signals and window size

| # | Case | Expected | Result | Notes |
| --- | --- | --- | --- | --- |
| S1 | Start a second session while a draft is open in session 1, then switch | The draft is cleared on the switch. No text reaches either session. | | |
| S2 | Switch sessions while `sleep 100` runs in session 1 | It keeps running. Switching back shows it still running, and Ctrl-C ends it. | | |
| S3 | Switch sessions immediately after Transmit | Text goes only to the original session, exactly once. | | |
| S4 | Exit a session (`exit`) and create a new one | A new shell appears. No old draft is replayed. | | |
| T1 | Ctrl-C inside a running TUI (`top`, `less`, an agent CLI) | The TUI handles or exits on SIGINT. The shell survives. | | |
| T2 | Ctrl-D inside a TUI or REPL (`node`, `python` if present) | EOF is handled by the program. | | |
| W1 | Rotate portrait ↔ landscape while `top` or `vi` is open | The program redraws at the new size. `stty size` changes accordingly. | | |
| W2 | Split screen / pop-up window, then resize the divider | Same as W1. | | |
| W3 | DeX window resize | Same as W1. | | |
| W4 | Run `tools/acceptance/agent-cli-check.sh --resize` and rotate | It reports PASS for SIGWINCH. | | |

### 6.4 Background and restart

| # | Case | Expected | Result | Notes |
| --- | --- | --- | --- | --- |
| B1 | Start `sleep 600; echo done`, press Home, wait 10 minutes, return | It is still running, or it finished and printed `done`. Record the One UI settings in use. | | |
| B2 | Same with the screen off for 10 minutes | Same as B1. | | |
| B3 | Recents › swipe the app away, then reopen | Record whether the session survives (the foreground service keeps it) and whether a draft was replayed (it must not be). | | |
| B4 | Force stop (Settings › Apps › Force stop), then reopen | A new session starts. No draft is restored. | | |
| B5 | 40 background jobs (section 5) with default phantom settings, then with the restriction disabled | Record kills in both configurations. | | |

## 7. Agent CLI rows

Run [AGENT-CLI-ACCEPTANCE.md](AGENT-CLI-ACCEPTANCE.md) in an APK with a bundled
bootstrap, once under each IME. Record its summary output there and
reference it here.

## 8. Evidence without leaking secrets

- Keep the app log level at **Normal**: Settings › commandroid-line › Debugging ›
  Log Level. At Verbose, upstream code logs execution commands and their
  arguments. NeoTerm itself never logs input text or key events.
- Capture only the app's process and system kill messages:

  ```sh
  adb logcat -c
  adb logcat -v time --pid="$(adb shell pidof -s com.commandroid_line)" > app.log
  adb logcat -v time -b main,system -s ActivityManager:I > am.log   # process kills, phantom limits
  ```

  Before sharing a log, search it for any fixture or credential text you typed
  (`grep -n -i 'fixture\|key\|token' app.log`).
- Screen recordings: use the One UI screen recorder, or run
  `adb shell screenrecord --time-limit 180 /sdcard/Movies/cl-K5.mp4` and then
  `adb pull`. Screen recordings show everything typed on screen. Use fixture text
  only, and stop the recording before entering any real credential (for example
  an agent CLI login).
- Store evidence outside the repository: keep it locally, or attach it to the pull
  request or a GitHub Release. Do not commit APKs, recordings or logs.

## 9. Results template

Copy this into the PR description or a results file and fill it in. Leave rows
blank or mark them `not run` rather than guessing.

```text
commandroid-line device run
Device:        Galaxy S26 Ultra, Android __ / One UI __, build __, page size __
App commit:    ________   Bootstrap: none|sha256 __
IMEs:          Samsung Keyboard __, Gboard __     HW keyboard: __
Phantom/background settings used: __

Instrumented (section 4):
  connectedDebugAndroidTest: __ passed / __ failed / __ skipped

Manual matrix (P/F/S/N/A):
  K1..K17  Samsung: __________________   Gboard: __________________
  H1..H10  __________________
  S1..S4, T1..T2, W1..W4  __________________
  B1..B5   __________________
Agent CLIs (AGENT-CLI-ACCEPTANCE.md): Claude Code __, Codex __, OpenCode __

Failures (case, observed vs expected, evidence file):
  -
Not run, and why:
  -
```
