# Standalone NeoTerm verification

This ledger applies to the `com.neoterm` Android system-shell development build.
The implementation follows base `b650b532` on `feature/neoterm-standalone`.
Record the exact tested source revision or dirty-tree diff with each run.

Historical M1 results, public-key signatures and old APK hashes are preserved in
[VERIFICATION.md](VERIFICATION.md) and `APK-SHA256SUMS`. They do not verify this
application identity, signing key, runtime or APK.

## Current evidence ledger

Verified on 2026-10-29 in `/workspace/neoterm`. Product source is commit
`8dc78469`; bootstrap tooling is commit `3f677919`. Documentation-only changes
do not affect the APK. Toolchain: Temurin JDK 11.0.32.1+1, Gradle 7.6.4,
SDK/build-tools 30/30.0.3 and PTY NDK 22.1.7171670.

| Check | Result to record |
| --- | --- |
| Exact source revision/diff and environment | Product `8dc78469`, tools `3f677919`; pinned toolchain above |
| Pure Kotlin and complete Android JVM suite | PASS: 22 input + 145 emulator + 47 app = 214 tests; zero failures/errors/skips |
| Standalone identity, startup and runtime regressions | PASS: identity metadata, own directories/env/argv, primary-user startup and unsupported-profile denial, explicit manual shell. Four expected review-regression failures observed before fixes; all pass afterward. Dialog test additionally drains Android's queued button callback. |
| Bootstrap preparation safety | PASS: 11 Python tests and real prepared-source verification at pinned `5a6d1c1eb868795dce83a6c269387b9f82d21805`. No packages or container built. |
| App and instrumentation APK build | PASS: combined Gradle command below, exit 0 in 54 seconds |
| Lint and formatting checks | PASS: lint 0 errors/51 warnings; `git diff --check` clean. Warnings include retained upstream issues, inlined IME flag on API 24, and button styling. |
| APK identity/components/contracts | PASS: packaged manifest `com.neoterm`, label NeoTerm, no shared UID. Merged-manifest/compiled-resource tests cover retained Java components, distinct providers/permission/actions and shortcut targets. |
| Native payload | PASS: only PTY `libtermux.so` for arm64-v8a, armeabi-v7a, x86 and x86_64. No bootstrap ZIP or bootstrap native library embedded. |
| Signing and artifacts | PASS: `apksigner verify`, local Android Debug signer; exact hashes below |
| Connected instrumentation | Pending: device/Android version and outcome, or exact blocker |
| Coexistence with official Termux | Pending: both installed, distinct UID/data, independent launcher/shortcuts, existing Termux data preserved |
| Live Android shell/PTY | Pending: first launch, PATH/HOME/tmp, commands, UTF-8, resize, signals, multiple sessions and process exit |
| User/profile gate | Pending: primary user allowed; secondary/work/unknown identity blocked before runtime initialization |
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
The new instrumentation APK compiles 19 shared IME contract tests and one native
PTY/controller test; compilation alone does not establish their device results.

Artifacts:

| File under repository | Bytes | SHA-256 |
| --- | ---: | --- |
| `app/build/outputs/apk/debug/neoterm_standalone-debug_universal.apk` | 6604338 | `5c63bd8ecd10b29897f16d6ca8ba12179bfe9e287a182988045f9d692eab0f27` |
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

A passing JVM suite cannot establish live JNI/PTY behavior, physical IME
compatibility or independent installation. Mark unrun checks pending and record
exact blockers rather than reusing archived acceptance claims.
