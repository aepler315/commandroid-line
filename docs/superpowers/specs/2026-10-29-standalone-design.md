# Standalone NeoTerm development build

> **Historical, superseded.** This document predates PR #2 and describes the
> earlier `com.neoterm` / `com.termux` builds. Anything it calls current is not.
> The current design is [ADR-0001](../../neoterm/ADR-0001-one-pty-terminal.md) and
> [ARCHITECTURE.md](../../neoterm/ARCHITECTURE.md); current results are in
> [TESTING.md](../../neoterm/TESTING.md).

The user's latest correction and instruction to continue supersede Milestone 1's
com.termux identity restriction. The next APK must identify as NeoTerm/com.neoterm,
use its own UID/private paths and never update or access an existing Termux app.

## Engineering decision
Changing identity with the old bootstrap is incompatible. A full custom-prefix
bootstrap is the intended Linux distribution path, but requires a separate build
of its package closure. Make the independent terminal useful immediately using
Android's /system/bin/sh and system commands with NeoTerm-private HOME and temp.
Clearly label it as Android system shell, with no apt/node/AI CLI runtime yet.
Do not offer a renamed APK pretending those package dependencies are resolved.

Preserve the emulator, PTY JNI, Kotlin composition engine and direct keys. Keep
com.termux Java namespaces and component class names; separate Android application
identity, intent contracts, permission/provider authorities and launcher branding.
Remove shared UID and upstream bootstrap native build/download from this target.
No automatic migration/import from Termux, public release, production signing,
repository push or deployment. Use ordinary debug signing in this development build.

A dedicated NeoTerm ShellEnvironmentClient selects /system/bin, no LD_PRELOAD,
explicit system shell/default working dir, UTF-8 terminal flags and app-private
HOME/PREFIX/TMPDIR. First-launch initialization creates only own directories and
never loads the old bootstrap. Surface a clear runtime notice in the terminal UI.

Test identity/manifest/component resolution, shortcut targets and permissions,
installer/session integration and shell environment through JVM tests. Build APK,
audit embedded manifest/native libraries and attempt connected instrumentation.
Device coexistence and native shell launch remain unverified without a device.
Prepare the genuine custom-prefix source bootstrap pipeline and record requirements.
