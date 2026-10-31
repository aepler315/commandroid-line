# Standalone NeoTerm implementation plan

> **Historical, superseded.** This document predates PR #2 and describes the
> earlier `com.neoterm` / `com.termux` builds. Anything it calls current is not.
> The current design is [ADR-0001](../../neoterm/ADR-0001-one-pty-terminal.md) and
> [ARCHITECTURE.md](../../neoterm/ARCHITECTURE.md); current results are in
> [TESTING.md](../../neoterm/TESTING.md).

Spec: docs/superpowers/specs/2026-10-29-standalone-design.md
User authorization: latest request for standalone and Continue; routine choices delegated.

1. Identity/packaging: test-first manifest app ID com.neoterm, label NeoTerm,
   isolated actions/paths/permissions, own UID, preserved Java component classes.
   Remove incompatible app bootstrap native dependency, retain emulator JNI.
2. Runtime vertical slice: tests for own data directories, system-shell argv/PATH,
   no preload/old prefix, first-launch setup and service environment routing.
   Implement native shell startup without Termux installer; clear runtime UI notice.
3. Source bootstrap pipeline: pin matching termux-packages source revision, provide
   validated preparation/build instructions and scripts; record precise blockers.
4. Full 200+ JVM suite, APK/test APK/lint, manifest and native-entry audit, connected
   test attempt, independent review, fix regressions, commit and deliver privately.

Review focus: Java-vs-app package names, installer bypass, background tasks,
old-prefix references, imported sessions, component/shortcut resolution, unsafe
bootstrap reuse, privacy and old APK links, no public release claims.
