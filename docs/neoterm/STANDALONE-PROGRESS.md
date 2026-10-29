# Standalone execution ledger

Plan: docs/superpowers/plans/2026-10-29-standalone.md
Base: b650b532. Branch feature/neoterm-standalone. User explicitly requests separate
installation, superseding initial compatibility milestone restrictions.
- Design: isolate package/UID/data; keep Java/JNI namespace; use Android system
  shell while custom-prefix Linux package build is pending. No unsupported bootstrap
  rebasing or claims of AI CLI runtime availability. Disclosure sent to user.
- Parallel work: identity/packaging; independent source-bootstrap research/tooling.
  Root owns runtime and full integration verification.
