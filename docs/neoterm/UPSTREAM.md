# Upstream and licensing

Inspected 2026-10-29. Source: https://github.com/termux/termux-app.git.
GitHub latest stable release: v0.118.3, published 2025-05-22;
commit **5b657c6adf4304e5198951ce815fe0205dcac29c**.
Newer v0.119.0 tags are beta releases. Development branch:
`feature/neoterm-standalone`, following the input milestone on
`feature/neoterm-input-engine`, originally based directly on the stable tag.
The standalone work starts from `b650b532`. The original remote remains origin.
The unrelated /workspace/Midio-5 checkout was not modified.

The application and new NeoTerm code are GPL-3.0-only. Preserve LICENSE.md,
termux-shared/LICENSE.md and per-file notices: terminal emulator/view include
Apache-2.0 Android Terminal Emulator code; shared code includes MIT, Apache-2.0
and GPL-2.0 with Classpath exception components. Review each component's notices
when distributing. A binary distributor must provide corresponding source,
including NeoTerm changes and build scripts, and required license texts/notices.
The name NeoTerm does not imply affiliation with or endorsement by Termux.
No upstream copyright notices or assets have been removed.

Milestone 1 retained Termux's package identity and bootstrap; its verification is
archived. The current application ID is `com.neoterm`, with no shared UID and a
private Android system-shell environment. Java/resource/JNI namespaces remain
upstream-compatible. No upstream-prefix bootstrap is embedded or installed.
Debug signing uses Android's generated local key. Production signing, compatible
Linux packages and real-device coexistence remain separate acceptance work.
See [MIGRATION.md](MIGRATION.md) and [BOOTSTRAP-BUILD.md](BOOTSTRAP-BUILD.md).

Full texts for the principal repository licenses and Classpath exception are kept
in LICENSES/ (verbatim SPDX license-list-data texts). Per-file exceptions remain
as specified by upstream; adding texts does not relicense those components.
Review all transitive dependency notices before public binary distribution.
