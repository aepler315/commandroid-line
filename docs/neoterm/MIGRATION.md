# Plan for an independently installable NeoTerm

Milestone 1 intentionally retains com.termux and /data/data/com.termux/files/usr.
Changing applicationId alone is not a valid migration. Current APKs are development
builds and do not provide side-by-side compatibility with Termux.

## Evidence inspected 2026-10-29
- Stable TermuxConstants.java (lines around 223–250 and 534–548) documents hardcoded
  prefixes, matching applicationId and bootstrap rebuild requirements:
  https://github.com/termux/termux-app/blob/v0.118.3/termux-shared/src/main/java/com/termux/shared/termux/TermuxConstants.java
- Stable AndroidManifest.xml uses package-derived sharedUserId, component and
  permission names; TermuxAppSharedPreferences validates app identity.
- app/build.gradle pins four bootstraps; termux-bootstrap-zip.S embeds the ABI zip.
- Current termux-packages scripts/properties.sh exposes TERMUX_APP__PACKAGE_NAME,
  TERMUX__ROOTFS and TERMUX__PREFIX (older aliases TERMUX_APP_PACKAGE, TERMUX_PREFIX):
  https://github.com/termux/termux-packages/blob/master/scripts/properties.sh
  Pin a specific packages revision when executing migration; master was research,
  not a build dependency. Package-name/path-length constraints also apply.

## Concrete migration sequence

| Area | Required work | Acceptance evidence |
| --- | --- | --- |
| Package identity | Reserve a short distinct ID; update Gradle applicationId, constants, manifest placeholders, providers, permissions, intents, shortcuts, resources and integration clients together. Audit every hardcoded com.termux path/name. | Fresh install with Termux present, separate UID/data, all components resolve correctly. |
| Linux prefix | Choose /data/data/NEW_ID/files/usr and home paths; rebuild toolchain/packages with that prefix. Audit shebangs, ELF RUNPATH, scripts, symlinks, pkg-config, Python/node modules and executable wrappers. Do not symlink to another app's private directory. | ELF/script scan and execution tests contain no unintended old prefix. |
| Bootstrap distribution | Pin compatible termux-packages revision; generate bootstraps for arm64, armv7, x86 and x86_64 using the new identity/prefix. Host immutable signed/checksummed releases and configure download/embed paths. | Offline clean-device bootstrap and package upgrade tests for all supported ABIs. |
| Native libraries | Keep Java namespace separate from applicationId where possible. Audit JNI symbols, library loading, exec/environment paths and Android linker behavior; rebuild JNI/bootstrap libraries. | PTY spawn, signals, UTF-8, exec and child process tests on each ABI. |
| App signing | Create protected production signing key with backup/recovery and documented ownership. Use stable signing across updates. Do not ship the upstream public debug key. Review/remove shared UID design before release. | Upgrade signature/rollback policy tested; plugins signed and authorized as required. |
| Packages/repositories | Publish a rebuilt, signed apt repository using new-prefix packages. Upstream .deb binaries are not assumed relocatable. Select/audit rebuild set including bash, apt, coreutils, Python, node and agent dependencies. | apt install/upgrade, pip/npm tools, agents and dependency closure run without old paths. |
| Storage/permissions | Separate HOME/PREFIX and shared-storage links. Audit scoped storage, SAF import/export, executability restrictions, background/foreground services, notifications and target-SDK changes independently. | Supported Android API matrix; no access to Termux's private data or inherited permission assumptions. |
| Updates/migrations | Version both Android app and bootstrap/repository schema. Reinstall packages into new prefix; import only explicitly selected user files/config with preview and backup. Absolute paths and virtualenvs need reconstruction. | Transactional import, dry run/report, interruption recovery and no automatic old-app deletion. |

## Shipping gates
1. Complete the identity/prefix inventory and pin source/toolchain/license manifests.
2. Build new-prefix bootstrap and minimal repository in CI; validate on clean devices.
3. Run side-by-side and upgrade suites, including plugins and Android shared-UID
   limitations. Document which Termux add-ons are incompatible or need forks.
4. Provide user-controlled archive export/import (old app cannot be read directly
   under Android sandboxing), checksums, ownership/permissions and symlink defenses.
   Never copy the old PREFIX wholesale as the migration strategy.
5. Establish signing, release-source publication, security/upstream update cadence,
   mirror ownership and rollback before changing the production package identity.

No identity/prefix/signing migration has been executed in this milestone.
