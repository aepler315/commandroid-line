# Standalone runtime and input acceptance

The current build uses `com.neoterm` and Android's `/system/bin/sh`. Its startup
supports the primary Android system user only; secondary users and work profiles
must receive an explanatory dialog before runtime directories or a shell start.
Compatible Linux packages and AI coding CLIs are not available. Current outcomes
belong in [STANDALONE-VERIFICATION.md](STANDALONE-VERIFICATION.md); earlier M1
results in [VERIFICATION.md](VERIFICATION.md) are archived.

## Automated layers
Pure Kotlin InputEngineTest tests lifecycle, mode policy, transmission bytes,
control rejection, malformed Unicode, reconnect/reset, rapid events and failures.
Shared ImeContract runs against real Editable/InputConnection implementations in
Robolectric SDK 28 and in CompositionInstrumentationTest on a connected device.
DirectInputTest captures the real TerminalSession queue without starting JNI; it
checks command/flag bytes, hardware Delete, Enter, Ctrl+C/D/Z, Alt and arrow escapes,
direct password log suppression, stale IMEs and actual DECSET paste capability.
CompositionLifecycleTest covers stale context-menu callbacks and native batch
cleanup after invalidation. InputFocusTest exercises the real Activity, local
composer, UI transmission/submission, resume focus and terminal queue. JVM-only
fixtures do not start a Linux process; these are not end-to-end PTY/agent tests.

StandaloneIdentityTest reads merged Android package metadata and compiled shortcut
intents for identity, UID declaration, component classes, private constants,
authorities, permission/action isolation and shortcut extras. AndroidShellEnvironmentTest
checks private directory setup, preservation of existing data, system PATH and
loader isolation, and script arguments. StandaloneStartupTest checks first-run
setup and rejects unsupported or unidentifiable Android users/profiles. None of
these JVM fixtures proves actual Android install/UID allocation or live PTY exec.

| Required behavior | Automated coverage | Physical/device follow-up |
| --- | --- | --- |
| Shell entry, paths and flags | Engine exact text; direct queue; system environment | Android sh and platform commands; bash/zsh after compatible packages exist |
| Natural-language paragraph | Engine and native draft | Correct a full paragraph in each keyboard |
| Autocorrect/selection replacement | Native composing region + commit | Suggestion strip correction and undo |
| Composition updates/cancellation | Repeated updates, empty composing replacement | Japanese/Korean candidate conversion |
| Swipe commits | Native multiword commit | Real Gboard/Samsung swipe |
| Backspace, composed and locally committed | Native replacement/deletion | Long-press delete, autocorrect undo |
| Backspace after transmission | No writes from empty/stale draft | Verify receiving application text unchanged |
| Cursor/selection during composition | Native selection and composing region | Touch handles, RTL, select-all, reverse selection |
| Multiline | Staged LF, bracketed-paste requirement | Current shell must reject when unadvertised; future readline/agents need no-submission acceptance |
| Emoji/Unicode | Surrogates, codepoint deletion, combining and scripts | ZWJ/grapheme deletion, Indic, CJK, Arabic |
| Escapes/modifiers | Rejected in draft; preserved direct queue | Extra-key sticky Ctrl/Alt, external keyboards |
| Modes | AUTO explicit intent, TEXT, TERMINAL, stale generations | Focus and suggestion strip after switching |
| Prompt submission | IME actions do not send; separate CR | Controlled shell read now; agents after runtime availability |
| Password privacy | No key logs, no saved/autofill draft | TERMINAL password read with echo off; logcat audit |
| Rapid callbacks | 1000 engine edits, 300 native updates in batch | Fast swipe/correction/mode toggles |
| Restart/reconnection | Reset/rebind/stale callbacks | Activity/process kill, service reconnect, session switch |

## On-device instrumentation
Use a test API 24+ device/emulator under the primary Android user, keeping any
existing Termux installation/data intact. Install/build matching debug/test APKs:

```sh
./gradlew :app:connectedDebugAndroidTest
# or, after installing both APKs:
adb shell am instrument -w com.neoterm.test/androidx.test.runner.AndroidJUnitRunner
```

The shared contract invokes Android APIs directly on the UI thread. It tests the
app's IME interface but does not drive an actual third-party keyboard. Run it on
API 24, 28, 30 and current Android, including a Samsung device. Record the current
device availability, command outcome and exact blockers in the standalone ledger.
A compiled test APK is not a passed instrumentation run.

## Installation, isolation and live runtime acceptance

These checks remain device acceptance requirements until recorded with evidence:

1. Install NeoTerm while official Termux remains installed. Confirm packages
   `com.neoterm` and `com.termux`, distinct assigned UIDs and separate data. Open
   both apps; preserve a user-created fixture in each home and confirm an action
   in one app does not alter the other fixture. Never delete real user data.
2. Inspect the APK and installed metadata: no shared UID, application label
   NeoTerm, own authorities/permission, existing `com.termux.*` component classes.
   Exercise new session, failsafe and settings shortcuts; each must open NeoTerm.
3. On first launch verify a working `/system/bin/sh` prompt, private HOME/tmp and
   system PATH. Use `pwd`, `id` and shell builtins to record observed paths/UID.
   Confirm no bootstrap installation/download occurs and no `com.termux` private
   directory is used. The reserved PREFIX must not imply apt/pkg availability.
4. Check Unicode output/input, PTY resize, multiple sessions, Ctrl+C/D/Z, arrows,
   shell exit, background/resume and service reconnection. Test the supported ABI
   and Android/vendor matrix; platform command availability can vary.
5. Under a secondary user and a managed work profile, verify the unsupported-user
   dialog, no HOME/tmp creation or shell startup, and activity exit after OK.
   This build does not advertise profile support while shared paths are static.
6. Upgrade NeoTerm using the same local debug key and verify its fixture remains.
   Record signature mismatch as a key-management issue if using another machine;
   do not uninstall Termux to work around a NeoTerm installation failure.

## Manual acceptance (release gate, not yet executed)
Record device model/Android build, IME package/version/language/settings, app commit,
receiving app version, and bracketed-paste state. Use non-sensitive fixture text.
Never record password contents or enable keystroke logging.

1. In TERMINAL, use Android sh and a controlled shell input fixture, such as
   `read -r draft`, followed by `printf '%s\n' "$draft"` to inspect the accepted
   line. Verify exact paths, flags, Tab, Ctrl+C/D/Z, Alt, Esc, arrows, slash, pipe
   and backtick where the shell supports them. Include a Bluetooth/USB keyboard.
2. In AUTO, verify direct input has no predictive rewriting. Tap Compose, then
   use Gboard and Samsung predictive text, autocorrect and swipe to write a
   multi-sentence paragraph. The draft label must remain visible; no bytes should
   reach the terminal while typing, correcting, moving cursor, finishing
   composition, changing keyboard, or pressing keyboard Enter/Done/Send.
3. Select and replace words, move touch handles, cancel candidates, paste emoji,
   combining marks and non-ASCII text. Verify the whole draft visually. Transmit
   once. Verify received bytes/text exactly once and no appended submission.
4. Press terminal Enter separately. Confirm only this action submits. Try
   multiline input with bracketed paste disabled: it must stay local with an
   explanation. Do not assume Android sh advertises bracketed paste. Repeat the
   permitted multiline case with a controlled compatible receiver when available.
5. Switch sessions and modes during composition and immediately after Transmit.
   No stale callbacks may send to the wrong session or repopulate sent text.
   Reconnect the same running session: do not replay sent text. Kill/recreate the
   app: the local draft is intentionally lost and must never be replayed.
6. Use TERMINAL for a password prompt with echo disabled. Verify diagnostic logs
   contain neither input text nor individual key/codepoint events. TEXT offers
   third-party keyboard predictions and is not a secure password editor.

Automated surrogate safety does not promise one-grapheme deletion for all emoji
sequences. Android/IME handles grapheme editing; test vendor behavior explicitly.
No displayed-text classifier can certify which terminal app owns input.

## Future Linux/agent acceptance

After compatible bootstrap/packages or another verified Linux runtime exist, run
the same matrix with bash/zsh and the actual Claude Code, Codex CLI and Gemini CLI
versions intended for support. Record runtime, package/dependency versions and
bracketed-paste behavior. Do not mark this matrix passed from a shell-only APK,
source preparation tooling, JVM queue tests or an archived M1 build.
