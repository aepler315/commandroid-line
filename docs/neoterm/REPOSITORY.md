# Repository initialization

Repository: https://github.com/aepler315/commandroid-line

`main` starts at the unmodified official Termux v0.118.3 commit
`5b657c6adf4304e5198951ce815fe0205dcac29c`. The `feature/neoterm-standalone`
branch contains the input milestone, independent app identity, system-shell
runtime, tests, source-bootstrap preparation and follow-up input fixes. The
initial pull request compares that feature branch against the stable baseline.

The original execution environment became unavailable. Recovery used the complete
Git bundle ending at `000acc78` plus the exact standalone2 source ZIP associated
with original commit `9391aa6ed08d525e47bfe48f4f4276046b16ac2f`.
The ZIP SHA-256 is
`344e71012ad9a3585192f3fa5c78e2e6495782b7d870ddcf06946808a8ee0b78`.
Every restored file was compared byte-for-byte with the archive before committing.
The two newest original commit objects were not in the bundle, so their final
contents are preserved in a clearly labeled recovery commit. Earlier upstream
and NeoTerm Git history is retained.

The original remote is now named `upstream`; `origin` is the user's repository.
No force push, release publication, deployment or automatic merge is needed.
Current APK/Android validation evidence is documented in ENTER-AND-APT.md and
the earlier standalone reports. Those results came from the original workspace;
the new GitHub workflow supplies independent checks for the repository import.
