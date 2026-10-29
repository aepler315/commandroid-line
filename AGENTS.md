# NeoTerm engineering guide

NeoTerm is an independent GPL-3.0-only development fork of Termux. Read
`docs/neoterm/ARCHITECTURE.md`, `UPSTREAM.md`, and `BUILD.md` before changes.

## Boundaries
- `terminal-emulator/`: upstream terminal parser, screen, session and JNI PTY.
- `terminal-view/`: upstream renderer, gestures, direct keys and InputConnection.
- `termux-shared/`: upstream runtime, filesystem, permissions and shared UI.
- `app/`: upstream Android activity, service, installer and session management.
- `neoterm-input/`: NeoTerm pure Kotlin input policy/state machine; no Android or PTY.
- `app/src/main/java/com/neoterm/input/`: NeoTerm Android editor/controller.
- `docs/neoterm/`: NeoTerm design, migration, verification and roadmap.

Keep upstream changes narrow and marked NeoTerm. Never infer terminal ownership
of text/cursor from the screen. Android Editable owns draft text, selection and
composition; never mirror these into a second editable buffer. Only explicit
Transmit writes draft text. IME commit/finish and Enter in the draft never submit.
Do not repair already-sent input with generated deletes. Preserve direct key,
modifier, escape, native runtime and package-manager behavior.

## Build and verification
Use JDK 11, pinned Gradle wrapper, SDK 30/build-tools 30.0.3, NDK 22.1.7171670.
Set `ANDROID_HOME` and local.properties as described in BUILD.md.
- `./gradlew :neoterm-input:test` — pure state/policy tests.
- `./gradlew testDebugUnitTest` — Android JVM and upstream regressions.
- `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest` — APKs.
- `./gradlew :app:connectedDebugAndroidTest` — attached Android device tests.
Use tests first for input changes. Test composition replacement/cancellation,
Unicode, stale callbacks, mode changes and transmission/submission separately.
Real Gboard/Samsung testing is required before any production support claim.
Record commands, actual outcomes and blockers; never call unrun checks passed.

## Standards and restrictions
Kotlin for new input logic, Java interop for narrow upstream hooks. UI operations
run on Android's main thread. Prefer Android's standard IME implementation.
Never log input, passwords, key events or text snapshots. No draft persistence,
analytics or automatic clipboard copying. User-initiated native editing actions
remain available. Keep all copyright and license notices.
The user now requires a separately installable build. Use `com.neoterm` and its
own HOME/PREFIX. Keep Java/JNI namespaces as com.termux for compatibility. Never
embed or install com.termux-prefix bootstrap binaries into the NeoTerm sandbox.
The standalone Android system-shell runtime has no apt/node/AI CLI support until
custom-prefix packages are built. Do not claim device coexistence without a device test. No publication, deployment,
remote pushes or PR creation without explicit user authorization. Work on
feature/neoterm-standalone; do not overwrite unrelated work.
