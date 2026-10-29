# Upstream and licensing

Inspected 2026-10-29. Source: https://github.com/termux/termux-app.git.
GitHub latest stable release: v0.118.3, published 2025-05-22;
commit **5b657c6adf4304e5198951ce815fe0205dcac29c**.
Newer v0.119.0 tags are beta releases. Development branch:
`feature/neoterm-input-engine`, based directly on the stable tag. The original
remote remains origin. No NeoTerm Milestone 1 archive was found in the workspace.
The unrelated /workspace/Midio-5 checkout was not modified.

The application and new NeoTerm code are GPL-3.0-only. Preserve LICENSE.md,
termux-shared/LICENSE.md and per-file notices: terminal emulator/view include
Apache-2.0 Android Terminal Emulator code; shared code includes MIT, Apache-2.0
and GPL-2.0 with Classpath exception components. Review each component's notices
when distributing. A binary distributor must provide corresponding source,
including NeoTerm changes and build scripts, and required license texts/notices.
The name NeoTerm does not imply affiliation with or endorsement by Termux.
No upstream copyright notices or assets have been removed.

Milestone 1 keeps Termux launcher/package identity for bootstrap compatibility.
It is a development fork, not an official Termux update. The public upstream
test signing key is unsuitable for a production release. See MIGRATION.md.
