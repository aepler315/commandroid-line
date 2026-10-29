# Archived Milestone 1 review

This review covers the historical input milestone, not the current standalone
identity/runtime changes. See [STANDALONE-VERIFICATION.md](STANDALONE-VERIFICATION.md)
for current verification status.

An independent read-only review examined stable upstream
5b657c6adf4304e5198951ce815fe0205dcac29c through implementation commit
8b3bcf64d9a2bf940c1eb88013dd541bdd868309 using the
superpowers:requesting-code-review workflow. The author implemented fixes and
verified them by reproducing each issue before the fix and running the full suite.
No second review or production acceptance is claimed.

| Finding | Resolution | Regression |
| --- | --- | --- |
| Stale connections cannot balance or close outstanding native batches | Track and drain only the connection's owned batches despite invalidation; never let stale close finish a fresh composition | CompositionLifecycleTest: stale end/close across Transmit, Clear, mode, session and connection changes |
| Resume/delayed keyboard handling steals focus for TerminalView during TEXT | Select the active input view for lifecycle focus and keyboard targeting | InputFocusTest: resume retains editor focus; hardware Enter edits locally and leaves terminal queue untouched |
| Inherited context-menu action bypasses stale generation guard | Gate menu and other mutating standard callbacks | CompositionLifecycleTest: stale paste/cut/select-all rejected across lifecycle transitions; current paste still replaces selection |
| New text retains old sent status | Reset status on draft changes | InputFocusTest: real UI/queue transmission followed by a new draft restores the unsent label |

The reviewer graded the label issue Minor; the author treated it as Important
because the required distinction between a draft and actual terminal input must
remain accurate. No review findings are deferred.

Review limits: actual Gboard/Samsung callback timing, physical accessibility/layout,
PTY/agent interpretation, large-paste responsiveness, whole-grapheme deletion,
third-party privacy-flag compliance, production signing and independent packaging.
These are recorded in TESTING.md, ROADMAP.md and MIGRATION.md and still require
acceptance evidence. The review found no Critical issue in the examined range;
this is not a security certification or a claim that all possible defects are absent.
