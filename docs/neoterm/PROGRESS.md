# Execution ledger
Plan: docs/superpowers/plans/2026-10-29-neoterm-input.md
- Baseline: stable v0.118.3; clean new clone, isolated from unrelated project.
- Pre-flight: engine Draft interface consumed by Android editor; TerminalTarget
  consumed by controller adapter. One Editable authority, no stored cursor mirror.
- Decision: work in the explicitly requested new clone/development branch; no
  extra worktree needed. User explicitly authorized routine decisions and execution.
- Baseline tests and SDK provisioning started; outcomes pending.
- Task 1 complete: 22 pure Kotlin tests, RED 17 failures against policy stub,
  GREEN 22/22. Stable upstream baseline GREEN 147/147 after fixing local proxy
  inheritance for Robolectric artifact downloads. No upstream product fix required.
- Task 2 started: shared Android InputConnection contract, 19 tests; widget policy
  intentionally not yet implemented. Android JVM RED pending execution.
- Task 2: native contract RED 7/19 against unconfigured native widget; after fixes
  GREEN 19/19. Covered UTF-16 half-surrogate deletion and old Android cursor=0.
- Additional direct-connection regression RED: late callbacks wrote after composer
  activation. Added generation/ownership gate and retained upstream direct bytes.
- Integrated pre-tooling-upgrade suite GREEN 194/194 (22 engine, 145 upstream
  emulator, 19 native editor, 6 direct queue, 2 upstream app).
- Decision: upgrade AGP 4.2.2/Gradle 7.2 to 7.4.2/7.6.4 because old lint's embedded
  Kotlin cannot read 1.8 metadata. Preserve SDK/min/target, NDK, bootstrap and runtime.
- Lint's AppCompatCustomView check is intentionally suppressed for this widget:
  TermuxActivity extends platform Activity and uses native widget/theme behavior.
- On-device execution blocker: adb devices reports no attached device; /dev/kvm
  is unavailable. Instrumentation APK build remains separate from execution.
- Modern lint found one existing upstream indentation error in TermuxService: an
  unconditional assignment was indented beneath a one-line if. Dedented only;
  no execution behavior changed. Rechecking lint after this whitespace fix.
- Debug script first rejected JDK 11 when JAVA_TOOL_OPTIONS added a stderr banner
  to javac -version. Adjusted version matching to accept the banner; reran script.
