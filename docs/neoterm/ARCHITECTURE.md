# NeoTerm standalone architecture

## Objective and constraints
Compose and correct a full prompt with a familiar Android IME before sending it
to a running terminal program. Keep all speculative text outside the PTY. Preserve
Termux's emulator, PTY JNI and direct input path while giving NeoTerm an independent
Android identity and system-shell runtime. Agent use remains a future acceptance
target because compatible Linux packages are not yet available.

## Android identity and runtime
The installed application ID is `com.neoterm`; the source/resource namespace stays
`com.termux` so upstream component classes and JNI symbols remain valid. A separate
Java namespace constant supplies component class names. Package-derived actions,
permissions, provider authorities, shortcuts and preferences use `com.neoterm`.
The manifest declares no shared UID. Debug builds use Android's local generated
debug key; release signing is not configured.

`com.neoterm.runtime.AndroidShellEnvironment` derives HOME/PREFIX/TMPDIR from
`Context.filesDir`. On the primary user these correspond to
`/data/data/com.neoterm/files/home`, `files/usr` and `files/usr/tmp`; Android may
return the equivalent `/data/user/0/com.neoterm/files` path. Startup creates only
private directories. The reserved PREFIX contains no Linux package environment.
Both foreground sessions and background tasks use this environment client.

The shell is Android's `/system/bin/sh` with a fixed system PATH and selected
Android runtime variables. Loader injection and arbitrary inherited shell settings
are excluded. Plain scripts use the system shell; declared shebangs are preserved.
The app never extracts official `com.termux`-prefix packages. Its old bootstrap
native build/download pipeline is disabled; terminal-emulator still supplies PTY
JNI. See [MIGRATION.md](MIGRATION.md) for the remaining package work.

Startup supports the primary Android system user only. It checks identity before
creating runtime directories and fails closed for secondary/work profiles or an
unavailable user identity. This keeps static primary-user paths in shared upstream
code from resolving to another profile's directories. Profile support needs a
broader context-derived path migration and device acceptance.

The system shell has ordinary application UID/SELinux permissions. It supplies
neither adb-shell privileges nor apt/pkg, bash, Python, Node, SSH or AI coding CLIs.
The input panel displays this runtime limit. Device coexistence, supported Android
versions and real PTY behavior still require the tests in [TESTING.md](TESTING.md).

## Inspected upstream flow
- terminal-view/.../TerminalView.java onCreateInputConnection creates a
  BaseInputConnection; commitText and finishComposingText send its Editable and
  clear it. deleteSurroundingText emits terminal Delete key events.
- TerminalView.inputCodePoint/onKeyDown translate Unicode/modifiers and keys.
- terminal-emulator/.../TerminalSession.java write queues UTF-8 to the JNI PTY.
- TerminalEmulator.java parses output, tracks alternate screen and DECSET 2004.
- app/.../TermuxActivity.java owns terminal, drawer and extra-key ViewPager.
- app/.../terminal/TermuxTerminalSessionClient.java selects/reconnects sessions.
- app/.../terminal/io/TerminalToolbarViewPager.java has an older send-on-action
  text field; NeoTerm uses its own explicitly staged editor and reuses only the
  existing extra-key page. The competing text page is removed from this fork.

## Approaches and engineering decision
A direct predictive InputConnection cannot safely correct text already owned by
an arbitrary shell/editor. A custom keyboard loses the familiar IME. Use a native
EditText with standard composition/selection and a narrow Kotlin lifecycle engine.
This is an engineering decision under the user's authorization to proceed without
routine design questions, not a claim of separate design approval.

## Ownership and state
Android Editable is the only draft text/cursor/selection/composing-span authority.
The pure Kotlin engine sees a Draft interface; it derives EMPTY/EDITING/COMPOSING
from that interface rather than maintaining a text/cursor mirror. DIRECT means
terminal owns input. A short TRANSMITTING state prevents reentrant send.
All callbacks run on the main thread. InputConnectionWrapper delegates Android
operations through the engine gate. Connection generations reject callbacks after
mode changes, session changes, new connections or transmission; restartInput
refreshes the IME. Native batch cleanup is tracked per connection and can outlive
invalidation without mutating a new composition. Context-menu and other mutating
callbacks use the same generation gate. Activity resume and delayed keyboard
requests target the active editor. finishComposingText finalizes only local spans. commitText
means local committed text, never terminal transmission or submission.

AUTO is default. With no trustworthy application protocol it stays direct; tapping
Compose is an explicit user signal to stage text in AUTO. TEXT opens composition;
TERMINAL returns focus to TerminalView. Drafts survive mode changes in memory for
the same session, never silently transmit. Session changes clear drafts, invalidate
old connections and reset AUTO's explicit composition intent. Process/activity
recreation starts with an empty draft (no saved state, disk, logs or replay).

## UI and transport
A compact always-visible mode row provides AUTO/TEXT/TERMINAL, Compose and Keys.
The editor appears only for TEXT or explicitly opened AUTO composition. A labeled
"Draft — not sent" area has Transmit, Clear and separate terminal Enter controls.
Hardware Enter and IME actions in the draft insert newline or finish local input;
they never write to the terminal. Terminal hotkeys stay on the existing view.
The existing optional extra-key toolbar is toggled with Keys.

Transmit finishes local composition, validates scalar Unicode and control input,
and writes one exact payload to the bound running session. It never appends Enter.
Multiline and tab-containing drafts require terminal DECSET 2004; permitted paste
uses bracketed framing and preserves LF. Unbracketed drafts permit printable text
only. Reject ESC, CR, C0/C1 controls except LF/TAB and malformed surrogates, so a
paste cannot inject a bracketed-paste terminator or terminal shortcut. No trimming,
case conversion or normalization. Clear draft only after successful handoff.
PTY queue handoff is not an acknowledgment from the foreground application;
no automatic retry or exactly-once delivery guarantee across process death.
Bracketed paste is an application contract, not proof of a safe prompt context.

## Privacy and testing
The composer is for non-sensitive natural language. No ECHO/password classification
is claimed. Use TERMINAL for passwords; it preserves no predictive context and key
logging is disabled even if upstream diagnostics request it. The composer disables
view-state saving/autofill and requests no personalized learning (IME behavior is
vendor controlled); it cannot guarantee third-party keyboard privacy.

Pure JVM tests exercise engine transitions, output bytes and privacy of diagnostics.
Robolectric and instrumentation tests exercise actual Android Editable and
InputConnection operations. Real Gboard/Samsung swipe, autocorrect and reconnection
must be validated on devices using the matrix in TESTING.md before release.
