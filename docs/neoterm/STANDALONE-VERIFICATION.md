# Standalone NeoTerm verification

> **Historical, superseded.** This document predates PR #2 and describes the
> earlier `com.neoterm` / `com.termux` builds. Anything it calls current is not.
> The current design is [ADR-0001](ADR-0001-one-pty-terminal.md) and
> [ARCHITECTURE.md](ARCHITECTURE.md); current results are in
> [TESTING.md](TESTING.md).

This ledger applies to the `com.neoterm` Android system-shell development build.
The implementation follows base `b650b532` on `feature/neoterm-standalone`.
Record the exact tested source revision or dirty-tree diff with each run.

Historical M1 results, public-key signatures and old APK hashes are preserved in
[VERIFICATION.md](VERIFICATION.md) and `APK-SHA256SUMS`. They do not verify this
application identity, signing key, runtime or APK.

## Current evidence ledger

Verified on 2026-10-29 in `/workspace/neoterm`. Product source is commit
`c212568b` (identity/runtime in `8dc78469`); bootstrap tooling is commit `3f677919`. Documentation-only changes
do not affect the APK. Toolchain: Temurin JDK 11.0.32.1+1, Gradle 7.6.4,
SDK/build-tools 30/30.0.3 and PTY NDK 22.1.7171670.

| Check | Result to record |
| --- | --- |
| Exact source revision/diff and environment | Product `c212568b`, tools `3f677919`; pinned toolchain above |
| Pure Kotlin and complete Android JVM suite | PASS: 22 input + 145 emulator + 49 app = 216 tests; zero failures/errors/skips |
| Standalone identity, startup and runtime regressions | PASS: identity metadata, own directories/env/argv, primary-user startup and unsupported-profile denial, explicit manual shell. Four expected review-regression failures observed before fixes; all pass afterward. Dialog test additionally drains Android's queued button callback. |
| Bootstrap preparation safety | PASS: 11 Python tests and real prepared-source verification at pinned `5a6d1c1eb868795dce83a6c269387b9f82d21805`. No packages or container built. |
| App and instrumentation APK build | PASS: combined Gradle command below, exit 0 in 38 seconds |
| Lint and formatting checks | PASS: lint 0 errors/52 warnings; `git diff --check` clean. Warnings include retained upstream issues, inlined IME flag on API 24, button styling and intentional input-surface overdraw to isolate contrast from terminal palettes. |
| Visible mode/draft controls | PASS: screenshot exposed black labels inherited from upstream light theme on a black window. Two regression tests failed before the fix, then passed for both host themes and black/white terminal palettes (text/hint contrast at least 4.5:1). Input subtree now has its own dark widget theme and opaque surface. |
| APK identity/components/contracts | PASS: packaged manifest `com.neoterm`, label NeoTerm, no shared UID. Merged-manifest/compiled-resource tests cover retained Java components, distinct providers/permission/actions and shortcut targets. |
| Native payload | PASS: only PTY `libtermux.so` for arm64-v8a, armeabi-v7a, x86 and x86_64. No bootstrap ZIP or bootstrap native library embedded. |
| Signing and artifacts | PASS: `apksigner verify`, local Android Debug signer; exact hashes below |
| Connected instrumentation | PASS: Android 9/API 28 x86_64, emulator-5554, TCG; all 20 tests pass in 26.669 seconds on the corrected APK. Command exit 0. |
| Coexistence with official Termux | PASS: both installed; original UID 10067, NeoTerm UID 10068. Original sentinel unchanged after install/tests; official app remains stopped/not launched. This does not certify all providers/shortcuts or physical devices. |
| Live Android shell/PTY | PASS: own-home shell launch, additional native session, corrected café/Chinese/emoji paragraph reaches read/printf exactly once after Transmit plus separate Enter; no pre-transmission bytes or fixture-content app logs. Comprehensive resize/signals/restart/device matrix remains pending. PATH/tmp/argv additionally covered by JVM tests. |
| User/profile gate | PASS: primary user on emulator; secondary/work/unknown identity blocked before runtime initialization in JVM regressions. Profile-device tests remain pending. |
| Real Gboard/Samsung and hardware keyboard | Pending: [TESTING.md](TESTING.md) matrix |

Build/test command (with `JAVA_HOME`, managed proxy/CA settings from BUILD.md):

```sh
export TERMUX_APP_VERSION_NAME=0.118.3-neoterm.standalone1
export TERMUX_APK_VERSION_TAG=standalone-debug
export TERMUX_SPLIT_APKS_FOR_DEBUG_BUILDS=0
./gradlew :neoterm-input:test testDebugUnitTest :app:assembleDebug \
  :app:assembleDebugAndroidTest :app:lintDebug --no-daemon --max-workers=2
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tools/bootstrap -p 'test_*.py'
python3 tools/bootstrap/bootstrap.py verify-source \
  /workspace/scratch/bootstrap-research/prepared-neoterm-verified
```

The complete JVM counts come from JUnit XML, not merely the Gradle exit code.
The instrumentation APK runs 19 shared IME contract tests and one native
PTY/controller test against real Android Editable, activity, controller and JNI.
These invoke standard InputConnection methods; they do not execute vendor IMEs.

Device command after installing both APKs:

```sh
adb -s emulator-5554 shell am instrument -w \
  com.neoterm.test/androidx.test.runner.AndroidJUnitRunner
```

The first one-core software guest stalled even on basic commands. Recovery with
two emulated cores, 2GiB RAM, 480x800 and density 160 completed boot and installation.
Initial `am start -W` first-draw wait timed out after 11.4 seconds, followed by a
responsive shell and passing instrumentation with no ANR modal. Physical-device
startup performance remains unmeasured. Logs are retained in workspace scratch:
`neoterm-contrast-install.log`, `neoterm-contrast-instrumentation.log` and
`neoterm-contrast-coexistence-audit.json`. The corrected launch reports Status: ok.
Small final evidence artifacts are checked in: [device test output](evidence/api28-instrumentation.txt),
[coexistence audit](evidence/api28-coexistence.json) and [readable Text-mode draft](evidence/api28-text-draft.png).
The screenshot's harmless local draft was never transmitted. Reinstalling the
corrected APK retained NeoTerm UID 10068, and the original sentinel stayed unchanged.

Official Termux v0.118.3 x86_64 APK SHA-256:
`3550e61f4d9eb49b712fd1bd9519dc37085a4d8eb597c57a340f0a64859b7144`.
Original private sentinel SHA-256 before and after device tests:
`97ec6ac165739c9fc0be12c9a31db67bbef061e242311b70744a827260faceea`.
The official app/bootstrap was never launched. Only the test sentinel was created
explicitly through `run-as` before the NeoTerm installation.

Artifacts:

| File under repository | Bytes | SHA-256 |
| --- | ---: | --- |
| `app/build/outputs/apk/debug/neoterm_standalone-debug_universal.apk` | 6604338 | `682352a32b3ed6aacbf937029e21b95bb272e76c8840a09d648940a5658a0661` |
| `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` | 342053 | `c9ed1d819956a632b8bd4eafc37454eb40043391c8d18480527e6f32668bbc7a` |

App signer certificate SHA-256:
`06537a927e8f014ef91381792e6f32e22d3a30c99892c1c7fe1df1f81a369305`.
This is a local development key, distinct from the archived public Termux debug
key. It is not a production signing or update-channel commitment.

## Scope and release limits

The source uses `com.neoterm`, no shared UID, Android's local debug signing and a
private `/system/bin/sh` environment. It does not bundle or install an upstream
Termux bootstrap. Compatible Linux packages, apt/pkg, Python, Node, SSH and AI
coding CLIs are not available. Bootstrap source preparation/static validation
tooling is documented in [BOOTSTRAP-BUILD.md](BOOTSTRAP-BUILD.md); a full source
build and Android execution remain separate work.
Startup supports the primary Android system user only; secondary users and work
profiles are blocked because shared upstream paths retain primary-user assumptions.

Full bootstrap compilation was not attempted. Build-environment preflight exits 1
with `Set NDK to an installed Android NDK r27c`; JDK17 is also absent. The package
builder requires different tools from the app build, and available disk is 17GiB
against 40+GiB recommended, with the Docker vfs driver duplicating layers. These
are provisioning/resource prerequisites, not evidence of a compiled bootstrap.

A passing JVM suite cannot establish live JNI/PTY behavior, physical IME
compatibility or independent installation. Mark unrun checks pending and record
exact blockers rather than reusing archived acceptance claims.
