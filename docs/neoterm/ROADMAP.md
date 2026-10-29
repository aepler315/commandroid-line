# NeoTerm roadmap and outstanding risks

## Implemented development scope
Native IME draft, pure Kotlin lifecycle, explicit transmission and separate Enter,
conservative AUTO and preserved direct key pipeline originated in Milestone 1.
The current branch adds `com.neoterm`, no shared UID, local debug signing, isolated
data/contracts and Android system-shell startup without an upstream bootstrap.
See [STANDALONE-VERIFICATION.md](STANDALONE-VERIFICATION.md) for current evidence;
the old M1 test/build record is archived. Production Gboard/Samsung certification
and actual device coexistence remain acceptance work.

## Next priorities
1. Run the standalone installation, live PTY and real IME matrix in TESTING.md.
   Confirm coexistence with official Termux without modifying its package/data.
   Agent acceptance follows availability of a compatible Linux runtime. Add regression
   fixtures for every vendor discrepancy. Test session switches, app backgrounding,
   restarts, hardware modifiers and keyboard layout changes under load.
2. Add a trustworthy shell/agent integration protocol for explicit natural-language,
   command and sensitive-context signals. Authenticate and scope it to a session;
   arbitrary escape output or a window title must not silently enable autocorrect.
   AUTO remains conservative until this is proved.
3. Improve in-memory lifecycle behavior, accessibility, focus restoration, narrow
   landscape layouts, keyboard action handling and grapheme-rich editing based on
   device evidence. Consider per-session draft retention only with explicit privacy
   requirements; current switches/recreation intentionally discard unsent drafts.
4. Evaluate terminal queue backpressure and acknowledged transmission UX. Current
   TerminalSession.write is a queue handoff, not an app acknowledgment; avoid auto
   retry/exactly-once claims and assess large-paste blocking of the main thread.
5. Build and validate the custom-prefix Linux runtime described in MIGRATION.md
   and BOOTSTRAP-BUILD.md. Source preparation tooling is present; complete bootstrap
   builds, a signed package repository and AI CLI dependency closures are pending.
   Establish protected release signing/source availability and an upstream security
   patch process before production distribution.

## Technical risks and present limits
- No arbitrary-app detection or termios ECHO/password detector is implemented.
  TEXT is explicitly selected for non-sensitive prose; use TERMINAL for secrets.
- IMEs can ignore NO_PERSONALIZED_LEARNING. Drafts are not persisted/autofilled or
  logged by NeoTerm, but a third-party keyboard remains outside app control.
- Bracketed paste is a receiving application's advertised protocol; a broken or
  hostile receiver can still interpret pasted input unexpectedly. Unbracketed
  multiline/tab drafts are blocked. Printable text can have application actions;
  NeoTerm cannot certify arbitrary terminal behavior.
- Staged control characters and malformed UTF-16 are blocked; direct mode retains
  Termux behavior and must be tested with target keyboards and agents.
- Android Editable owns grapheme/selection behavior. Surrogate deletion is guarded;
  whole-grapheme deletion of every combining/ZWJ sequence is not independently
  implemented or certified.
- Vendor IME/cursor-anchor, batch-edit, reconnection and focus timing need physical
  testing. Current JVM checks do not execute a real IME, PTY or AI agent.
- App/activity recreation and switching sessions discard unsent drafts deliberately.
  No crash recovery or replay is offered. Failed/partial transport requires manual
  inspection before retrying; terminal write has no receiver acknowledgment.
- Stable upstream uses old Android target/API assumptions and dependencies. Tooling
  updates do not resolve modern background execution, distribution policy, storage
  or security-maintenance obligations. Plan these separately from input changes.
- Package metadata now isolates NeoTerm from official Termux. Real-device separate
  UID/data, install/upgrade behavior and component resolution still need evidence.
- Shared upstream paths still assume the primary Android user. Startup blocks
  secondary users/work profiles before initialization; supporting them requires a
  complete context-derived path migration and profile-specific device tests.
- The Android system shell offers only platform commands allowed to an app UID.
  There is no package manager, Python, Node, SSH or agent runtime. Official Termux
  packages must never be installed or copied into the new prefix as a shortcut.
- Local debug keys differ across build machines; developer updates require the
  same key. Protected production signing and an upgrade policy are still pending.
