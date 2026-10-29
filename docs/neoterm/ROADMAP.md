# NeoTerm roadmap and outstanding risks

## Milestone 1: explicit local composition
Native IME draft, pure Kotlin lifecycle, explicit transmission and separate Enter,
conservative AUTO, preserved direct key pipeline, tests and compatible debug build.
A compiled milestone is not production Gboard/Samsung certification.

## Next priorities
1. Run the real IME/device/agent acceptance matrix in TESTING.md. Add regression
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
5. Execute the independent-package migration in MIGRATION.md, establish release
   signing/source availability and an upstream security patch process.

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
- Current com.termux identity/public debug signing cannot coexist with an official
  installation. Independent identity requires rebuilt prefix/package ecosystem.
