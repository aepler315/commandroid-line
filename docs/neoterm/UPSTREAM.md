# Upstream and licensing

Source: https://github.com/termux/termux-app.git, inspected 2026-10-29. The latest
stable GitHub release is v0.118.3, published 2025-05-22, at commit
**5b657c6adf4304e5198951ce815fe0205dcac29c**. Newer v0.119.0 tags are beta
releases. commandroid-line is based directly on the stable tag. The fork's history
is in [REPOSITORY.md](REPOSITORY.md): PR #1 (NeoTerm), PR #2 (Commandroid), then
the one-stack resolution in `85bb558`.

## What is kept from upstream

The JNI PTY (`terminal-emulator`), `TerminalEmulator`, `TerminalView`,
`TermuxActivity`, `TermuxService`, the session client, settings and
`termux-shared` are kept, with narrow, marked changes. The Java, resource and JNI
namespaces stay `com.termux`. The compat flavor's `BootstrapInstaller` is
upstream v0.118.3 `TermuxInstaller` extraction logic, changed in two ways: it
reads the archive from APK assets, and it refuses entries that would escape the
staging prefix.

## What differs from upstream

- Application IDs are `com.commandroid_line` (compat) and
  `com.commandroid_line.system` (system). There is no shared UID.
  `TermuxConstants.TERMUX_PACKAGE_NAME` comes from the termux-shared flavor's
  `BuildConfig`.
- Debug builds use Android's locally generated key, and release signing is not
  configured.
- No upstream (`com.termux`-prefix) bootstrap is embedded, downloaded or
  installed. compat installs only a bootstrap built from source for its own
  prefix ([BOOTSTRAP-BUILD.md](BOOTSTRAP-BUILD.md)). system runs
  `/system/bin/sh` only.
- NeoTerm staged input (`:neoterm-input`, `com.neoterm.input`) and the Commandroid
  translator (`:cmd-translator`) are new code
  ([ARCHITECTURE.md](ARCHITECTURE.md)).
- The older send-on-action text page of the extra-keys toolbar is removed in
  favor of the NeoTerm composer.

## Licensing

The application and the new NeoTerm and commandroid-line code are GPL-3.0-only.
Preserve `LICENSE.md`, `termux-shared/LICENSE.md` and the per-file notices. The
terminal emulator and view include Apache-2.0 Android Terminal Emulator code.
The shared code includes MIT, Apache-2.0 and GPL-2.0-with-Classpath-exception
components. Review each component's notices when distributing. A binary
distributor must provide the corresponding source, including the
commandroid-line changes and build scripts, plus the required license texts and
notices. Packages in a bootstrap carry their own licenses, and the bootstrap
build must preserve them.

Full texts of the principal repository licenses and the Classpath exception are
kept in `LICENSES/`, as verbatim SPDX license-list-data texts. Per-file exceptions
remain as upstream specifies them; adding the texts does not relicense those
components. Review all transitive dependency notices before any public binary
distribution. No upstream copyright notices or assets have been removed.

The names commandroid-line, Commandroid and NeoTerm do not imply affiliation with
or endorsement by Termux.
