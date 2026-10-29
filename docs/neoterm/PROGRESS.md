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
