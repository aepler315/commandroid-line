# Roadmap and outstanding risks

## Implemented

- One PTY terminal stack (upstream `TerminalSession`/`TerminalEmulator`/`TerminalView`)
  with NeoTerm staged input. It has AUTO/TEXT/TERMINAL modes, an explicit
  Transmit without Enter, bracketed paste for multi-line text, and a separate
  Enter that writes only `\r` ([ARCHITECTURE.md](ARCHITECTURE.md)).
- The Commandroid CMD → POSIX translator as an opt-in Transmit rewrite. Its
  output is staged in the shell line and refusals keep the draft.
- One sideload app, `com.commandroid_line`, targetSdk 28: a bundled
  source-built bootstrap or the `/system/bin/sh` fallback. The targetSdk 34
  `system` flavor was removed in `b473b51` because the app will never be
  published on Google Play ([ADR-0001](ADR-0001-one-pty-terminal.md), amendment
  of 2026-10-31).
- The pipe-based Commandroid process manager, second launcher, MVI/Canvas UI and
  Hilt are retired.
- JVM suites, lint and the debug APK builds pass locally ([TESTING.md](TESTING.md)).
  Nothing has been tested on a device.

## Next priorities

1. **Bootstrap.** Build the bootstrap from source for
   `/data/data/com.commandroid_line/files/usr`, including Node.js and git
   ([BOOTSTRAP-BUILD.md](BOOTSTRAP-BUILD.md)). No build has succeeded yet; CI
   run #5 was in progress. Then build and sign an apt
   repository for that prefix so packages can be installed after first run.
2. **Agent CLIs.** Run [AGENT-CLI-ACCEPTANCE.md](AGENT-CLI-ACCEPTANCE.md) for
   Claude Code, Codex and OpenCode. Resolve native-binary availability for
   Android/Bionic where an npm package ships a prebuilt binary.
3. **Device.** Run [DEVICE-TEST-PROTOCOL.md](DEVICE-TEST-PROTOCOL.md) on the
   Galaxy S26 Ultra with Samsung Keyboard, Gboard and a hardware keyboard. Add a
   regression test for every vendor discrepancy found.
4. **CI.** Get the instrumented suite (`:app:connectedDebugAndroidTest`),
   including the PTY integration tests for interrupts, lifecycle, submission,
   Unicode and restarts, green on the API 34 and API 36 CI emulators for the
   single-app build. Done at `59f17d0`: both are green. The bootstrap-prefix
   test still skips until CI bundles a bootstrap.
5. **Translator defects.** Emit `--` before `rm`/`cp`/`mv` operands, and narrow the
   glued-`cd` rule so that words like `cd.sh` are not read as `cd`. Both are
   carried over from PR #2.
6. **16 KB pages.** Check, and if needed fix, 16 KB page-size compatibility of
   the PTY JNI library (NDK 22.1.7171670) and the bootstrap binaries.
7. **Integration protocol.** Add a trustworthy shell/agent integration protocol
   for natural-language, command and sensitive-context signals. It must be
   authenticated and scoped to a session. Arbitrary escape output or a window
   title must not switch modes or enable autocorrect. Until then AUTO stays
   conservative.
8. **Input polish.** Improve in-memory lifecycle behavior, accessibility, focus
   restoration, narrow landscape layouts and grapheme-rich editing, based on device
   evidence. Consider per-session draft retention only with explicit privacy
   requirements.
9. **Transmission.** Evaluate terminal queue backpressure and acknowledged
   transmission. `TerminalSession.write` is a queue handoff, not an acknowledgment.
10. **Release.** Set up protected release signing, a source-availability process
    and an upstream security-patch process before any distribution. Distribution
    is by sideloading only (never Google Play) and needs the owner's
    authorization.

## Risks and present limits

- **targetSdk 28.** The app is sideload only and cannot be published on Google
  Play (the owner does not intend to). Android may warn at install, and a future
  release may raise the minimum installable targetSdk. If that happens, the app
  stops installing on new devices. There is no longer a targetSdk 34 build to
  fall back to, and no supported replacement route is known (exec via
  `linker64` was rejected in ADR-0001).
- **Phantom process killer (Android 12+).** Agent CLIs that spawn many processes
  can be killed. Users must disable the restriction (Developer options or
  `device_config`), and the app cannot do it for them.
- **One UI background limits.** Samsung battery management can put the app to
  sleep. Users must set the app to Unrestricted or Never sleeping.
- **16 KB page-size devices.** Native code compatibility has not been checked.
- **CMD translation** is a convenience for a subset of CMD, not a shell. The
  prediction of `cd` across lines comes from a snapshot. Known defects (ADR-0001)
  are mitigated, but not fixed, by staging every translation for an explicit
  Enter.
- **Passwords.** There is no termios ECHO or password detector. TEXT is for
  non-sensitive prose; use TERMINAL for secrets. IMEs can ignore
  `NO_PERSONALIZED_LEARNING`.
- **Bracketed paste** is the receiving program's advertised protocol. A broken or
  hostile receiver can still misinterpret pasted input. Unbracketed multi-line and
  tab drafts are blocked.
- **Grapheme deletion.** Android `Editable` owns grapheme and selection behavior.
  Surrogate deletion is guarded, but whole-grapheme deletion of every combining or
  ZWJ sequence is not certified.
- **Vendor IME timing.** Cursor-anchor, batch-edit, reconnection and focus timing
  need physical testing. JVM checks do not run a real IME, PTY or agent.
- **Drafts are discarded deliberately.** Activity recreation and session switches
  drop unsent drafts. There is no crash recovery or replay, and no receiver
  acknowledgment of a write.
- **Primary Android user only.** Startup blocks secondary users and work profiles
  while shared upstream paths are static.
- **Debug keys differ across machines.** Developer updates need the same key.
  Production signing and an upgrade policy are pending.
- Official Termux packages or bootstraps must never be installed or copied into
  the prefix as a shortcut.
