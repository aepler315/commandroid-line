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
- Product commit `8dc78469`: 214 JVM tests pass; app + 20-test instrumentation APK
  compile; lint 0 errors/51 warnings. Device execution is pending separately.
- Bootstrap tooling commit `3f677919`: 11 Python safety tests and actual prepared
  source verification pass. Full 111-package bootstrap closure, compatible apt
  repository and AI coding CLIs remain unbuilt. NDK r27c/JDK17 are absent and the
  current 18GiB disk headroom/vfs builder is below recommended build capacity.
- Software API28 x86_64 emulator boots and official Termux v0.118.3 is installed;
  original-package sentinel/UID baseline recorded. Post-boot adb responsiveness
  is being investigated before claiming coexistence or native PTY acceptance.
- See STANDALONE-VERIFICATION.md for exact commands, counts, hashes and remaining
  device/physical-keyboard acceptance checks. Historical M1 APKs use com.termux.
