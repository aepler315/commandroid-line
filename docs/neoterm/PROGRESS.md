# Archived Milestone 1 execution ledger

> **Historical, superseded.** This document predates PR #2 and describes the
> earlier `com.neoterm` / `com.termux` builds. Anything it calls current is not.
> The current design is [ADR-0001](ADR-0001-one-pty-terminal.md) and
> [ARCHITECTURE.md](ARCHITECTURE.md); current results are in
> [TESTING.md](TESTING.md).

Historical input-milestone record. Current standalone work is tracked in
[STANDALONE-PROGRESS.md](STANDALONE-PROGRESS.md), with current verification in
[STANDALONE-VERIFICATION.md](STANDALONE-VERIFICATION.md).

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
- Task 2 committed as 8b3bcf64. Pinned debug script passed all 194 JVM tests and
  built universal debug + instrumentation APKs. Subsequent lint passed (0 errors,
  45 warnings); connected instrumentation failed exactly "No connected devices!".
- Whole-branch read-only review dispatched under requesting-code-review skill.
- Execution environment disconnected before any review-fix command started. On
  user-requested continuation, confirmed the checkout/toolchains were intact.
- Review regression rerun: 6 tests, 5 expected failures (batch end/close, stale menu,
  resume focus, stale sent label). One current-generation paste control passed.
- Final fixes: drain only each connection's owned native batches on invalidation,
  preserve fresh composition on stale close, gate context-menu/private/correction
  operations, route resume/keyboard callbacks to the active editor, and mark new
  text unsent. Final whole JVM suite GREEN 200/200, zero failures/errors/skips.
- Activity/queue integration also stages/corrects a full paragraph, transmits once
  with no Enter, rejects late duplicate commit, and keeps submission separate.
- Final review ruling: treat the stale sent label as important because unsent text
  must be distinguishable from terminal input. Fixed with the UI transmission test.
  No review minors deferred. Physical IMEs, layout/accessibility, end-to-end PTY/
  agents, grapheme editing, keyboard privacy compliance and independent packaging
  remain explicit device/release acceptance work, not inferred passes.
- Task 3 complete: final pinned debug build passed; 200/200 JVM tests, lint 0 errors
  and 45 warnings, signed app + instrumentation APKs. Connected instrumentation
  attempted after fixes and blocked only by no attached device. Real IME/agent
  acceptance remains pending. VERIFICATION.md records exact commands and artifacts.
- Reproducibility check: forced :app:packageDebug --rerun-tasks re-executed 105
  tasks and produced the same SHA-256 as the initial final build.
- Keeping the requested development branch and checkout; no publication, push,
  merge or PR. Final documentation/source attribution committed for handoff.
