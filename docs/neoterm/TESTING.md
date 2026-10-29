# Input verification and real IME acceptance

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

| Required behavior | Automated coverage | Physical/device follow-up |
| --- | --- | --- |
| Shell entry, paths and flags | Engine exact text; direct queue | bash/zsh, hardware and soft keys |
| Natural-language paragraph | Engine and native draft | Correct a full paragraph in each keyboard |
| Autocorrect/selection replacement | Native composing region + commit | Suggestion strip correction and undo |
| Composition updates/cancellation | Repeated updates, empty composing replacement | Japanese/Korean candidate conversion |
| Swipe commits | Native multiword commit | Real Gboard/Samsung swipe |
| Backspace, composed and locally committed | Native replacement/deletion | Long-press delete, autocorrect undo |
| Backspace after transmission | No writes from empty/stale draft | Verify receiving application text unchanged |
| Cursor/selection during composition | Native selection and composing region | Touch handles, RTL, select-all, reverse selection |
| Multiline | Staged LF, bracketed-paste requirement | bash/readline and each agent; no submission |
| Emoji/Unicode | Surrogates, codepoint deletion, combining and scripts | ZWJ/grapheme deletion, Indic, CJK, Arabic |
| Escapes/modifiers | Rejected in draft; preserved direct queue | Extra-key sticky Ctrl/Alt, external keyboards |
| Modes | AUTO explicit intent, TEXT, TERMINAL, stale generations | Focus and suggestion strip after switching |
| Prompt submission | IME actions do not send; separate CR | Agent receives only after explicit Enter |
| Password privacy | No key logs, no saved/autofill draft | TERMINAL password read with echo off; logcat audit |
| Rapid callbacks | 1000 engine edits, 300 native updates in batch | Fast swipe/correction/mode toggles |
| Restart/reconnection | Reset/rebind/stale callbacks | Activity/process kill, service reconnect, session switch |

## On-device instrumentation
Use a disposable API 24+ device/emulator. Install/build matching debug/test APKs:

```sh
./gradlew :app:connectedDebugAndroidTest
# or, after installing both APKs:
adb shell am instrument -w com.termux.test/androidx.test.runner.AndroidJUnitRunner
```

The shared contract invokes Android APIs directly on the UI thread. It tests the
app's IME interface but does not drive an actual third-party keyboard. Run it on
API 24, 28, 30 and current Android, including a Samsung device. There was no attached
Android device in the implementation environment; see VERIFICATION.md for actual
executed checks. A compiled test APK is not a passed instrumentation run.

## Manual acceptance (release gate, not yet executed)
Record device model/Android build, IME package/version/language/settings, app commit,
receiving app version, and bracketed-paste state. Use non-sensitive fixture text.
Never record password contents or enable keystroke logging.

1. In TERMINAL, start bash and the actual Claude Code, Codex CLI and Gemini CLI
   versions used for acceptance. Verify exact paths, flags, Tab, Ctrl+C/D/Z, Alt,
   Esc, arrows, slash, pipe and backtick. Include a Bluetooth/USB keyboard.
2. In AUTO, verify direct input has no predictive rewriting. Tap Compose, then
   use Gboard and Samsung predictive text, autocorrect and swipe to write a
   multi-sentence paragraph. The draft label must remain visible; no bytes should
   appear in the agent while typing, correcting, moving cursor, finishing
   composition, changing keyboard, or pressing keyboard Enter/Done/Send.
3. Select and replace words, move touch handles, cancel candidates, paste emoji,
   combining marks and non-ASCII text. Verify the whole draft visually. Transmit
   once. Verify received bytes/text exactly once and no appended submission.
4. Press terminal Enter separately. Confirm only this action submits. Try
   multiline input with bracketed paste disabled: it must stay local with an
   explanation. Enable bracketed paste in the receiving app and repeat.
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
