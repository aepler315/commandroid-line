# NeoTerm changelog

## 0.118.3-neoterm.m1 — development milestone (2026-10-29)

Based on official Termux v0.118.3, commit 5b657c6adf4304e5198951ce815fe0205dcac29c.
Independently developed GPL-3.0-only changes; existing upstream notices preserved.

- Added a pure Kotlin input lifecycle and transmission policy.
- Added native Android paragraph composition with suggestions/autocorrect flags,
  selection replacement, composition cancellation and Unicode deletion guards.
- Added visible AUTO/TEXT/TERMINAL modes; AUTO requires explicit Compose intent.
- Added clearly labeled local draft, Transmit, Clear and separate terminal Enter.
- Preserved upstream direct terminal key/modifier/escape behavior; blocked stale
  direct/composer input connections across mode, session and transmission changes.
- Disabled input-content diagnostic logging; disabled draft state saving/autofill.
- Retained the optional upstream extra keys; added backtick popup to slash and
  removed the older competing send-on-action text toolbar page.
- Fixed stale batch cleanup/context-menu callbacks and resume focus; new drafts
  restore the unsent status after transmission.
- Added pure JVM, Android JVM and shared on-device InputConnection contract tests.
- Pinned Gradle 7.6.4 (with checksum), AGP 7.4.2 and Kotlin 1.8.22 for debug builds.
- Documented architecture, build/test commands, migration, risks and roadmap.

Application identity, bootstrap checksums, Linux prefix, PTY and native runtime
remain upstream-compatible. Physical Gboard/Samsung and agent acceptance is pending;
see docs/neoterm/VERIFICATION.md for actual test and build evidence.
