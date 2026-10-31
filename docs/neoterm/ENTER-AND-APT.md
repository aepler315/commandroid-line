# Single physical Enter and apt progress

> **Historical, superseded.** This document predates PR #2 and describes the
> earlier `com.neoterm` / `com.termux` builds. Anything it calls current is not.
> The current design is [ADR-0001](ADR-0001-one-pty-terminal.md) and
> [ARCHITECTURE.md](ARCHITECTURE.md); current results are in
> [TESTING.md](TESTING.md).

2026-10-29. The user confirmed improved Bluetooth input after the focus fix,
then reported needing Enter twice in AUTO mode. Changes follow `6d9527fb`.

## Input correction

In direct input, a hardware Enter can reach the IME before the terminal, accepting
its composing word without submitting. Pending text in the native direct
InputConnection also needs ordering before terminal Enter. The new regression
failed before implementation. Unmodified physical Enter/keypad Enter now pass
through the terminal's pre-IME handler: finalize its existing native composing
buffer, invalidate the connection against late replay, restart input, and send
the normal upstream line-ending once. Key-up adds no line ending. Modified keys
and soft-keyboard events keep their upstream paths.

No competing buffer/cursor state is introduced. TEXT and AUTO's explicit Compose
editor do not use this handler: keyboard Enter stays a local newline, and
Transmit never appends Enter. Keypad Enter keeps upstream LF versus ordinary
Enter's CR. No guessed backspaces, autocorrect or input logging was added.

Verification with version `0.118.3-neoterm.standalone2`, tag `standalone-debug`,
JDK11 and the BUILD.md proxy/CA setup:

```sh
./gradlew :neoterm-input:test testDebugUnitTest :app:assembleDebug \
  :app:assembleDebugAndroidTest --no-daemon --max-workers=2
adb -s emulator-5554 install -r app/build/outputs/apk/debug/neoterm_standalone-debug_universal.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w \
  com.neoterm.test/androidx.test.runner.AndroidJUnitRunner
```

- PASS: 220 JVM tests (22 input, 145 emulator, 53 app), zero failures/errors/skips.
- PASS: app and instrumentation builds in 37s; signature verified unchanged.
- PASS: all 21 Android tests, API28 x86_64/TCG, 46.341s. The new live native shell
  test sends a command through Android keyboard events, then another through
  native direct IME composition. Each executes after just one physical-style
  Enter injection; each output appears once and late IME replay is rejected.
- Physical Bluetooth/Samsung/Gboard retesting remains pending. Injected Android
  key events do not certify a particular phone, IME or Bluetooth transport.

APK SHA-256:
`f4924aa3f907e45473ec1e186fb66b03d2ebb23fcbf5b62e62a3926b7c10116d`.
Package/signing stay compatible with the installed `com.neoterm` preview.
Prior standalone hash files and reports describe earlier APK revisions; the
workspace output path now contains standalone2.

## apt rather than pkg

`apt` is the selected native package manager already in tools/bootstrap/config.json.
`pkg` is a Termux convenience wrapper; removing or ignoring it does not make
official-prefix packages relocatable. NeoTerm needs apt, dpkg, their dependency
closure and all installed packages built for `/data/data/com.neoterm/files/usr`,
then a signed matching repository. No apt command or fake installer is added to
the Android system shell, and official Termux mirrors remain disabled.

Provisioned in this workspace:

- NDK r27c/27.2.12479018 at `/workspace/toolchains/android-ndk-r27c`, downloaded
  from Google's official archive; SHA-256 matches pinned config
  `59c2f6dc96743b5daf5d1626684640b20a6bd2b1d85b13156b90333741bad5cc`.
- Temurin JDK17.0.21+9 at `/workspace/toolchains/jdk-17.0.21+9`, official Adoptium
  GitHub release asset; verified against its published SHA-256
  `8b9b611d917e3dfd8419d3566b5949226f69b052fed7e109c1368add1dfe8711`.
- Existing app JDK11 and NDK22 are retained for app compatibility.

```sh
export NDK=/workspace/toolchains/android-ndk-r27c
export TERMUX_JAVA_HOME=/workspace/toolchains/jdk-17.0.21+9
python3 tools/bootstrap/bootstrap.py preflight \
  /workspace/scratch/bootstrap-research/prepared-neoterm-verified --build-env --docker
```

PASS: exact prepared source, effective com.neoterm prefix, NDK and JDK checks.
The local Docker daemon uses vfs and has no builder image. Only 14GiB remains,
versus the procedure's 40+GiB recommendation; vfs duplicates builder layers.
Full builder/package compilation was not attempted within this limited disk.
This is provisioning progress, not a compiled apt/bootstrap artifact.

Next runtime step: provision a dedicated builder with sufficient disk, execute
BOOTSTRAP-BUILD.md for one ABI, validate the real archive, then implement/test
installation and build the signed NeoTerm apt repository before enabling updates.
