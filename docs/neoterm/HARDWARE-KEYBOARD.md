# Physical keyboard focus follow-up

2026-10-29, after standalone preview commit `000acc78`. User reports Bluetooth
keyboard input failing; affected mode/device details are still pending.

Reproduced a related application defect independently of Bluetooth transport:
Tab/DPAD navigation can focus Transmit/Clear/Enter/Keys or a selected mode button.
Their activation did not restore focus to the editor/terminal. Subsequent physical
typing could remain on a button. The controller now returns focus after these
actions and after reselecting an unchanged mode. It does not forward draft text,
add Enter, or intercept navigation globally.

`InputFocusTest.hardwareNavigationReturnsToDraftAfterActions` failed before the
fix. It now checks native letter editing after each action, no changed draft
ownership, and returning focus after repeated TEXT/TERMINAL selection.

Verification with the BUILD.md toolchain and standalone version/tag environment:

```sh
./gradlew :neoterm-input:test testDebugUnitTest :app:assembleDebug \
  :app:assembleDebugAndroidTest --no-daemon --max-workers=2
```

PASS in 29s: 22 input + 145 emulator + 50 app = 217 JVM tests; zero failures,
errors or skips. App and instrumentation APKs build. APK signature verifies with
the same local debug certificate as the installed preview. `git diff --check`
passes. The application identity remains `com.neoterm`.

Patch APK SHA-256:
`e56296e3611dc8c81fe0508764d051240a03c0905f687f77179ef9cf03c16e3c`.
Download filename: `NeoTerm-standalone1-keyboard-focus-debug.apk`.
This is a source/hash revision of the standalone1 debug preview, retaining its
version metadata and signing key; it can update that preview without uninstalling.

No Android device is attached in this resumed environment. The baseline's 20
Android tests have **not** been rerun for this patch, nor has a physical Bluetooth
keyboard been tested. This establishes a focus fix, not resolution of every
possible Bluetooth pairing, mapping or input failure. Baseline results and hashes
in STANDALONE-VERIFICATION.md/STANDALONE-SHA256SUMS remain historical for `c212568b`;
the current build output path has been overwritten by this patch.

Device follow-up: test letters, Enter, arrows and Ctrl shortcuts in TERMINAL;
draft editing in TEXT; activate buttons via Tab/Enter, then resume typing; repeat
mode selection, background/resume and keyboard reconnect. Check that no draft
bytes reach the PTY before explicit Transmit. Record keyboard/phone/Android version
and whether tapping the intended terminal/draft restores typing. Never log keys,
text or private prompts to diagnose this issue.
