# Repository

Repository: https://github.com/aepler315/commandroid-line (`origin`). Upstream
Termux is https://github.com/termux/termux-app.

## Branches and history

- `main` began at the unmodified official Termux v0.118.3 commit
  `5b657c6adf4304e5198951ce815fe0205dcac29c`.
- PR #1 (`feature/neoterm-standalone`, merge `fab3397`) added the NeoTerm input
  milestone, the independent `com.neoterm` identity, the system-shell runtime, the
  source-bootstrap preparation tooling and follow-up input fixes.
- PR #2 (`feature/commandroid-spec-rebuild`, merge `4a09acb`) added the SDK 34 /
  JDK 17 toolchain, the Commandroid Windows CMD → POSIX translator, and a second,
  pipe-based terminal stack with its own launcher.
- Commit `85bb558` resolves the two stacks into one PTY terminal, keeps the
  translator as an opt-in Transmit rewrite, and adds two runtime flavors,
  `compat` (`com.commandroid_line`, targetSdk 28) and `system`
  (`com.commandroid_line.system`, targetSdk 34)
  ([ADR-0001](ADR-0001-one-pty-terminal.md)).
- Commit `b473b51` removes the `system` flavor and the flavor dimension, because
  the owner will never publish the app on Google Play. commandroid-line is now one
  sideload app, `com.commandroid_line`, targetSdk 28 (ADR-0001, amendment of
  2026-10-31).

Both feature branches are merged. New work goes on a topic branch and lands on
`main` through a pull request. Pushing topic branches and opening draft PRs for
review is the normal workflow. There is no force-pushing to `main`, no automatic
merging, and no release publication or deployment without the owner's explicit
authorization.

## Recovery record (historical)

The original NeoTerm execution environment became unavailable during the
`feature/neoterm-standalone` work. Recovery used two inputs: the complete Git
bundle ending at `000acc78`, and the exact standalone2 source ZIP associated with
original commit `9391aa6ed08d525e47bfe48f4f4276046b16ac2f` (SHA-256
`344e71012ad9a3585192f3fa5c78e2e6495782b7d870ddcf06946808a8ee0b78`). Every
restored file was compared byte for byte with the archive before it was
committed. The two newest original commit objects were missing from the bundle,
so their final contents are preserved in a clearly labeled recovery commit.
Earlier upstream and NeoTerm history is retained.

The verification documents from that period are historical. They describe
`com.neoterm` or `com.termux` builds from before PR #2 and say nothing about the
current APK: [STANDALONE-VERIFICATION.md](STANDALONE-VERIFICATION.md),
[ENTER-AND-APT.md](ENTER-AND-APT.md), [HARDWARE-KEYBOARD.md](HARDWARE-KEYBOARD.md),
[VERIFICATION.md](VERIFICATION.md), [PROGRESS.md](PROGRESS.md),
[STANDALONE-PROGRESS.md](STANDALONE-PROGRESS.md), [REVIEW.md](REVIEW.md),
[MIGRATION.md](MIGRATION.md), the plans and spec under `docs/superpowers/`,
`APK-SHA256SUMS`, `STANDALONE-SHA256SUMS` and `evidence/`.
