# Standalone execution ledger

Plan: docs/superpowers/plans/2026-10-29-standalone.md
Base: b650b532. Branch feature/neoterm-standalone. User explicitly requests separate
installation, superseding initial compatibility milestone restrictions.
- Design: isolate package/UID/data; keep Java/JNI namespace; use Android system
  shell while custom-prefix Linux package build is pending. No unsupported bootstrap
  rebasing or claims of AI CLI runtime availability. Disclosure sent to user.
- Parallel work: identity/packaging; independent source-bootstrap research/tooling.
  Root owns runtime and full integration verification.
- Implemented independent `com.neoterm` app identity, no shared UID, isolated
  Android contracts and own sandbox, preserving Java/resource/JNI namespace.
- Implemented Android system-shell environment and bootstrap-free startup, with
  a visible runtime limitation notice. Primary-user guard and explicit manual
  `/system/bin/sh` selection address review findings.
- Product commits `8dc78469`/`c212568b`: 216 JVM tests pass; app + instrumentation
  APK compile; lint 0 errors/52 warnings. Screenshot-discovered label contrast
  defect fixed, with two failing-then-passing palette/theme regression tests.
- Bootstrap tooling commit `3f677919`: 11 Python safety tests and actual prepared
  source verification pass. Full 111-package bootstrap closure, compatible apt
  repository and AI coding CLIs remain unbuilt. NDK r27c/JDK17 are absent and the
  current 18GiB disk headroom/vfs builder is below recommended build capacity.
- API28 x86_64 emulator recovered with 2 CPUs/2GiB; official Termux UID10067 and
  NeoTerm UID10068 coexist. Original sentinel unchanged. All 20 Android tests pass
  on the corrected APK in 26.669s, including real native PTY Unicode composition,
  explicit Transmit/Enter separation and fixture-log privacy. Physical vendor IMEs
  and complete Linux/agent runtime remain pending.
- See STANDALONE-VERIFICATION.md for exact commands, counts, hashes and remaining
  device/physical-keyboard acceptance checks. Historical M1 APKs use com.termux.
