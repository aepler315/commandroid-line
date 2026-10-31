# Standalone identity and Linux runtime migration

> **Historical, superseded.** This document predates PR #2 and describes the
> earlier `com.neoterm` / `com.termux` builds. Anything it calls current is not.
> The current design is [ADR-0001](ADR-0001-one-pty-terminal.md) and
> [ARCHITECTURE.md](ARCHITECTURE.md); current results are in
> [TESTING.md](TESTING.md).

The current source implements an independently packaged Android system-shell
NeoTerm. Linux packages and AI coding CLIs remain pending. Earlier M1 APKs used
`com.termux`; their verification is [archived](VERIFICATION.md). Current test,
APK and device evidence belongs in [STANDALONE-VERIFICATION.md](STANDALONE-VERIFICATION.md).

## Implemented in source

| Area | Current behavior | Remaining acceptance |
| --- | --- | --- |
| Android identity | Application ID `com.neoterm`, NeoTerm app label, no shared UID declaration. | Install with official Termux present; check distinct UID/data and independent upgrades. |
| Components and contracts | Java/resource namespace `com.termux` and JNI symbols retained. Component class constants use a separate Java namespace. Shortcuts target `com.neoterm`; authorities, permission, actions, extras and preferences use the new identity. | Inspect built manifest/resources; exercise launcher, settings, shortcuts, services and providers. |
| Private paths | Runtime derives HOME/PREFIX/tmp from `Context.filesDir`; shared constants use `/data/data/com.neoterm/files`. Startup blocks secondary/work profiles or unavailable user identity before initialization. | Validate primary-user aliases and storage/SAF behavior; broader path migration is required before profile support. |
| Shell startup | Creates private directories and starts Android `/system/bin/sh`; foreground and background execution use a dedicated environment client. | Live PTY spawn, commands, signals, resize, UTF-8, restart and process-exit checks on supported devices. |
| Bootstrap | Official-prefix bootstrap download/embedding and extraction are removed. Historical C/assembly sources are retained unused; terminal-emulator PTY JNI remains active. | Inspect APK contents and clean first-launch behavior; confirm no old prefix is accessed. |
| Signing | Android's generated local debug key replaces the public upstream debug key. Release signing remains unconfigured. | Developer upgrade with the same key; protected production signing and recovery policy. |

The runtime has ordinary Android application privileges. It supplies platform
shell commands, not adb-shell access or a Linux distribution. There is no apt/pkg,
bash, Python, Node, SSH or AI coding CLI environment. Upstream plugins rely on
their original package/signature/shared-UID contracts and are not supported as
NeoTerm add-ons by this migration.

## Remaining Linux runtime work

[BOOTSTRAP-BUILD.md](BOOTSTRAP-BUILD.md) documents pinned source preparation and
static validation tooling for the real prefix `/data/data/com.neoterm/files/usr`.
No completed compatible bootstrap or signed NeoTerm package repository is included.
Preparing source or passing static checks does not prove execution on Android.

1. Build compatible toolchain/package dependency closures from source for each
   distributed ABI. Keep `TERMUX_APP__NAMESPACE=com.termux` to match Java classes.
   Audit shebangs, ELF interpreter/RUNPATH, scripts, symlinks, pkg-config and Python/
   Node modules for old paths. Official Termux binaries are not relocatable inputs.
2. Validate real bootstrap archives and second-stage installation on clean devices,
   with official Termux still installed. Record exact source/configuration, build
   logs, component licenses, checksums and architecture coverage.
3. Publish a rebuilt signed package repository before advertising apt/pkg install
   or upgrades. Keep official mirrors disabled. Validate bash, core utilities,
   Python, Node, SSH and agent dependencies as explicit package sets.
4. Integrate verified archives into app startup with immutable checksums, runtime
   schema/versioning, failure recovery and per-ABI evidence. An unavailable ABI
   must never fall back to an upstream-prefix archive.
5. Test actual Claude Code, Codex CLI and Gemini CLI versions before claiming agent
   support, including their Android/runtime dependencies and the IME matrix.

A source-built proot plus a separately verified Linux rootfs is an alternative
future route, with its own packaging, storage, performance and device validation.
It is not part of the current system-shell build.

## User data and release work

NeoTerm never reads another application's private directory or automatically
copies/deletes an old installation. A future importer must be user initiated,
preview selected files, preserve a backup, and defend against archive path and
symlink escapes. Recreate packages, virtualenvs and absolute-path configurations
for the new prefix; never import the old PREFIX wholesale.

Before production distribution, establish a protected signing key with backup and
ownership, update/rollback policy, corresponding-source publication, upstream
security maintenance, and supported Android/API/vendor coverage. Modern storage,
background-service and target-SDK changes need their own acceptance tests.
