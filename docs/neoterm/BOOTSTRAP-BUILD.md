# Building a genuine commandroid-line bootstrap

commandroid-line targets SDK 28, the route Termux uses so binaries in the app
data directory can execute. Its application ID is `com.commandroid_line`
(Android IDs cannot contain hyphens, so the requested `com.commandroid-line` is
spelled with an underscore). Its native package prefix is
`/data/data/com.commandroid_line/files/usr`, and its home directory is
`/data/data/com.commandroid_line/files/home`. The Java namespace stays
`com.termux`.

Official Termux `.deb` files and bootstrap archives are compiled for another
app's private directory, and so are archives built for the earlier `com.neoterm`
identity. They must not be reused, byte-patched, or installed from official
Termux repositories in commandroid-line. Every package is rebuilt from source
for this prefix.

**Status: no bootstrap has been built successfully yet.** This repository
contains source preparation, static validation tooling and a CI workflow. The
first four CI builds stopped before producing an archive (the fixes they led to
are noted under "Source preparation"), and CI run #5 was in progress when this
was written. No `bootstrap-<arch>.zip` exists for this prefix, and nothing has
been executed on an Android device. The acceptance targets are Claude Code, Codex
and OpenCode. They need at least Node.js (`nodejs-lts`), git and a working
bash/coreutils/apt bootstrap. None of these has been shown to work yet.

## Pinned source and configuration

[config.json](../../tools/bootstrap/config.json) pins termux-packages commit
`5a6d1c1eb868795dce83a6c269387b9f82d21805`, tag
`bootstrap-2025.03.28-r1+apt-android-7`. This is the bootstrap revision selected by
upstream termux-app v0.118.3. It keeps the app's expected second-stage entry
point, `etc/termux/bootstrap/termux-bootstrap-second-stage.sh`. Current packages
master has moved that file and changed its toolchain, so it cannot be dropped in
as a build dependency for this app.

The header of upstream `scripts/build-bootstraps.sh` explicitly describes
building from local sources for forked Termux apps without first publishing an
apt repository. Its `--architectures` option accepts `aarch64`, `arm`, `i686`
and `x86_64`. The package identity is configured in `scripts/properties.sh`, not
by a command-line option of the bootstrap script. That file assigns
`TERMUX_APP__PACKAGE_NAME` directly, so exporting an environment variable does
not change the build prefix.

`bootstrap.py` refuses a configuration whose package name, data directory,
rootfs, prefix, home, app name, Java namespace or package manager differs from
the commandroid-line identity. This refuses both `com.termux` and `com.neoterm`.

### Underscore in the package name

Checked against the pinned source:

- `scripts/properties.sh:1975` defines
  `TERMUX_REGEX__APP_PACKAGE_NAME="^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+$"`.
  `scripts/properties.sh:312` applies it to `TERMUX_APP__PACKAGE_NAME`, and
  the validator at `scripts/properties.sh:2160-2168` enforces it. An underscore
  is accepted. A hyphen is rejected: sourcing the file with
  `com.commandroid-line` exits with "must be a valid android app package name".
- With `com.commandroid_line`, sourcing the prepared file passes every check,
  including the path-length checks at `scripts/properties.sh:2277-2331`. The
  prefix is 41 characters (limit below 90), the rootfs 37 (below 86), the
  `usr/tmp` path 45 (below 94), and the data directory 31 (below 69). The
  name is 20 characters, within upstream's suggested 21 or fewer
  (`scripts/properties.sh:297`).
- The runtime helpers of termux-core v0.3.0 (the bootstrap's `termux-core`)
  validate package names with the same `[a-zA-Z0-9_.]` character class. A
  hyphenated name would fall back to `com.termux` there.
- No `build.sh` or patch in the 120-package build closure below hardcodes
  `/data/data/com.termux`. Packages use `@TERMUX_PREFIX@` or
  `@TERMUX_APP_PACKAGE@` substitution. The only literal match is a comment in
  `packages/dpkg/lib-dpkg-path-remove.c.patch`. The termux-exec v2.3.0 and
  termux-core v0.3.0 Makefiles take the package name and paths from make
  variables. Only an actual build can show whether any upstream package source
  still embeds the official path. The artifact validator rejects that.

## Source preparation

Preparation makes these narrowly scoped source changes:

- `scripts/properties.sh`: set `TERMUX_APP__PACKAGE_NAME=com.commandroid_line`
  and `TERMUX_APP__NAME=commandroid-line`. The data directory, rootfs, prefix
  and home derive from those. Keep `TERMUX_APP__NAMESPACE=com.termux` because
  the Java classes keep their namespace. Keep the internal Termux
  environment-variable names compatible.
- `packages/apt/build.sh`: generate an empty, commented apt `sources.list`. A
  rebuilt, signed commandroid-line package repository is still needed for
  `pkg`/`apt install` and upgrades.
- `packages/termux-tools/build.sh`: ship no `etc/termux/mirrors` definitions.
  termux-tools v1.45.0 `pkg install|search|update|upgrade` call
  `select_mirror`. When `sources.list` has no active `deb` line, it writes a
  randomly chosen official Termux mirror into `sources.list`. That would undo
  the disabled sources on first use. Without mirror files, `pkg` fails instead.
  `termux-change-repo` likewise has nothing to select.
- `scripts/build-bootstraps.sh`: fix two omissions in this revision's
  local-source wrapper. Export `TERMUX_PACKAGE_MANAGER=apt` explicitly, and pass
  `TERMUX_ARCH` instead of the undefined `package_arch` to second-stage
  generation. Otherwise the package-manager and architecture substitutions in
  the second stage can be empty.
- `scripts/build-bootstraps.sh` also builds a subpackage-only name through its
  parent. The default list names `bzip2`, which exists only as
  `packages/libbz2/bzip2.subpackage.sh`. At this revision, `build-package.sh`
  rejects such a name with "No package bzip2 found in any of the enabled
  repositories", and `build_package` only tolerates the older "No build.sh
  script" message. Upstream's usual download-based bootstrap flow never reaches
  this path. The fourth CI build compiled apt's whole closure and bash, then
  stopped here.
- Checksum re-pins listed in `config.json` `checksum_repins`. Some hosts
  (Codeberg, GitLab) generate `/archive/` tarballs on the fly. When one is
  regenerated, its bytes and SHA-256 change even though its contents do not,
  and the pinned build stops with "Wrong checksum". A re-pin replaces exactly
  one pinned digest in one `build.sh`. It is added only after checking that
  the new archive's extracted tree is identical to the upstream git tag, and
  the entry records that check. Current re-pins:
  - `x11-packages/foot/build.sh` (foot 1.21.0; ncurses compiles `foot.info`
    from it). The first CI build failed on this archive. Its regenerated tree is
    identical (`diff -r`) to tag `1.21.0`, commit
    `68f5eab0b0fa08becebbed412947ba19246c2518`.

- Source URL rewrites listed in `config.json` `source_url_rewrites`. They move a
  download whose old location is gone. The pinned SHA-256 is unchanged and
  `termux_download` still enforces it, so the content cannot change. Each
  entry replaces an exact URL prefix an expected number of times in one
  `build.sh`, and records how the new location was checked. Current rewrites:
  - `packages/docbook-xml/build.sh`: `docbook.org/xml/` returns 404. Use
    `archive.docbook.org/xml/`, which is what upstream termux-packages master
    uses with the same five digests. The second CI build failed here.
  - `packages/debianutils/build.sh`: 5.21 is gone from `deb.debian.org`. Use
    `snapshot.debian.org` at 20241115T204403Z; the file there matches the pin.
  - `packages/nano/build.sh`: 8.3 is no longer under `dist/latest/`. Use
    `dist/v8/`; the file there matches the pin.
  - `packages/dos2unix/build.sh`: `waterlan.home.xs4all.nl` now serves an HTML
    page in place of 7.5.2. Use dos2unix's SourceForge release mirror; the file
    there matches the pin. The fifth CI build stopped here.
  - `packages/apr/build.sh`, `packages/apr-util/build.sh` and
    `packages/subversion/build.sh` (git's dependencies): `dlcdn.apache.org`
    and `downloads.apache.org` keep only current releases. apr 1.7.5 and
    apr-util 1.6.3 already return 404 there. Use `archive.apache.org/dist/`,
    whose files match the pins. The sixth CI build compiled every default
    package and stopped at apr.

  An availability scan of the closure's download URLs (2026-10-31) found these
  three, plus GitHub archive and gnupg.org URLs that failed only through the
  session proxy used for the scan. GitHub archive downloads succeed in CI.

  A static scan of the other on-the-fly archives in the bootstrap's dependency
  closure (apt, libxslt, freedesktop and Codeberg sources) found every pinned
  digest still matching, except libfcft 3.3.1. libfcft is a dependency of foot
  itself, which this bootstrap does not build, so it has no re-pin.

Each change must match its expected pinned upstream text exactly. The tool
checks the effective properties by sourcing the real configuration. It rejects
a different source revision, configuration drift, unexpected tracked changes, an
extra package without `packages/<name>/build.sh`, and an `output/` directory
that already contains `.deb` files.

## Extra packages

`extra_packages` in `config.json` lists packages added to upstream's default
bootstrap set with `build-bootstraps.sh --add`. The default is
`["nodejs-lts", "git"]`; use `[]` for upstream's default set only. Names are
validated, so the generated arguments are shell-safe:

```sh
python3 tools/bootstrap/bootstrap.py build-args --arch aarch64
# --architectures aarch64 --add nodejs-lts,git
python3 tools/bootstrap/bootstrap.py build-args --arch aarch64 --without-extra-packages
# --architectures aarch64
```

At the pinned revision, `--add`/`-a` takes a comma-separated list
(`scripts/build-bootstraps.sh:298-300` and `343-353`). Each name is appended to
the package list unless it is already present (`scripts/build-bootstraps.sh:464-470`).
Each package is then built from source with its dependencies. Two upstream
behaviours matter here:

- `build_package` ignores a "No build.sh script at package dir" failure
  (`scripts/build-bootstraps.sh:83-89`). A misspelled name, or a subpackage
  name, would silently be left out. `verify-source` therefore requires
  `packages/<name>/build.sh` for every extra package. `verify-artifact`
  requires each one to appear in `var/lib/dpkg/status`, and requires `bin/node`
  and `bin/git` to be compiled executables for the archive's ABI.
- `extract_debs` puts every `.deb` from `output/` into the archive
  (`scripts/build-bootstraps.sh:97-200`). Build-only dependencies and sibling
  subpackages are therefore installed in the bootstrap too, as with the default
  set.

The pinned `scripts/buildorder.py` resolves the same closure for aarch64 and
x86_64:

| Request | Prerequisites | New source builds beyond the default set |
| --- | --- | --- |
| default bootstrap (30 packages) | 111 distinct packages | — |
| `nodejs-lts` 22.14.0 | 7 (resolv-conf, c-ares, libc++, libicu, ca-certificates, zlib, openssl) | c-ares, libicu |
| `git` 2.49.0 | 47 | apr, apr-util, serf, subversion, utf8proc |
| both | — | 9 including the two requested packages; 120 in total |

The default set already builds `git`'s Perl, Tk and curl prerequisites.
`subversion` and its libraries come from the `git-svn` subpackage. `nodejs-lts`
also performs a host build of ICU and compiles V8, which is expected (not
measured) to be the longest single build in the closure.

Claude Code, Codex and OpenCode are not part of the bootstrap. Once Node.js
and npm work on a device, they would be installed with npm from the npm
registry. Agents that ship native per-platform binaries may need their own
Android builds. This is unverified.

## Preparation and preflight

Run from this repository, using a new dedicated checkout outside it:

```sh
export COMMANDROID_SOURCE=/workspace/scratch/commandroid-line-bootstrap
python3 tools/bootstrap/bootstrap.py prepare "$COMMANDROID_SOURCE"
python3 tools/bootstrap/bootstrap.py preflight "$COMMANDROID_SOURCE" --docker
```

These commands clone and prepare the source and make a read-only check of the
local Docker daemon. They do not start a container, install build dependencies
or compile. `prepare` refuses an existing destination rather than resetting
somebody's work. Use a fresh checkout and a dedicated build container for each
architecture.

On a host or an already provisioned builder, preflight can also check the
local toolchain:

```sh
export NDK=/path/to/android-ndk-r27c
export TERMUX_JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
python3 tools/bootstrap/bootstrap.py preflight "$COMMANDROID_SOURCE" --build-env
```

The package build requires **NDK r27c** (numeric revision `27.2.12479018`) and
**JDK 17**. The setup script verifies the NDK archive SHA-256
`59c2f6dc96743b5daf5d1626684640b20a6bd2b1d85b13156b90333741bad5cc`.
The app's own NDK r22 and JDK are separate build tools and do not satisfy this
package build. The pinned setup also selects SDK command-line tools revision
9123335 and Android build-tools 33.0.1.

## Full source build (local)

Provision a Linux amd64 machine with enough disk before running these steps.
The supplied [Dockerfile](../../tools/bootstrap/Dockerfile) derives from the
pinned upstream builder and uses Ubuntu 24.04. It installs the extensive build
dependencies and the correct SDK, NDK and JDK. It copies the *prepared*
`properties.sh`, so the image creates and owns `/data/data/com.commandroid_line`.
Do not pull the moving `ghcr.io/termux/package-builder:latest`: its current
toolchain targets newer package sources. The base tag and apt indices are live,
so record the final image ID, package versions, source diff and checksums with
release artifacts. This is a pinned source procedure; it does not claim
bit-for-bit reproducibility.

The Dockerfile's `proxy_ca` secret mount is optional (`required=false`). In a
managed cloud environment with a TLS-intercepting proxy, pass the session CA as
a BuildKit secret. The Dockerfile installs it for one build step and removes it
before the layer is committed. Ordinary builders, including GitHub runners, omit
`--secret`.

```sh
docker build --platform=linux/amd64 \
  ${PROXY_CA:+--secret id=proxy_ca,src="$PROXY_CA"} \
  --file tools/bootstrap/Dockerfile \
  --tag commandroid-line-package-builder:termux-5a6d1c1e \
  "$COMMANDROID_SOURCE/scripts"
docker image inspect --format '{{.Id}}' commandroid-line-package-builder:termux-5a6d1c1e
```

Run source verification again before compiling. The command below builds one
ABI with the configured extra packages. It uses the same seccomp profile as
upstream `scripts/run-docker.sh` and changes ownership of the dedicated checkout
to UID/GID 1001. Use a different checkout for the next ABI.

```sh
python3 tools/bootstrap/bootstrap.py verify-source "$COMMANDROID_SOURCE"
BUILD_ARGS="$(python3 tools/bootstrap/bootstrap.py build-args --arch aarch64)"
docker run --rm --init --platform=linux/amd64 --user root \
  --security-opt seccomp="$COMMANDROID_SOURCE/scripts/profile.json" \
  --mount "type=bind,src=$COMMANDROID_SOURCE,dst=/home/builder/termux-packages" \
  --env "BUILD_ARGS=$BUILD_ARGS" \
  commandroid-line-package-builder:termux-5a6d1c1e \
  bash -ec 'chown -R builder:builder /home/builder/termux-packages
    exec su --whitelist-environment=BUILD_ARGS - builder -c \
      "cd /home/builder/termux-packages && TERMUX_INSTALL_DEPS=false ./scripts/build-bootstraps.sh \$BUILD_ARGS"' \
  2>&1 | tee build-aarch64.log
```

Behind a TLS-intercepting proxy, also mount the session CA read-only. Install it
in the disposable container before `exec su`, and add
`--whitelist-environment=HTTP_PROXY,HTTPS_PROXY,NO_PROXY,http_proxy,https_proxy,no_proxy`.
Keep TLS verification enabled, and do not use host networking to work around
egress policy. Upstream's proot helpers (`termux_setup_proot`) need extra
privileges. No package in this closure uses them; only `pypy` and `pypy3` do at
this revision.

Use the default Android 7+ **apt** bootstrap mode. The script's `--android10`
option selects a different package-distribution system (`apk`); a device running
Android 10 or newer does not by itself require it. Do not pass the
dependency-download options `-i`/`-I`: they can bring in official-prefix target
packages. Also audit the build log for fallback downloads caused by circular
dependencies. At this revision `scripts/build/termux_download_deb_pac.sh:17-25`
refuses repository debs whose package name differs from ours, so such a
fallback fails the build instead of silently mixing prefixes. Source-build
support does not make any downloaded target `.deb` compatible.

The expected output is `$COMMANDROID_SOURCE/bootstrap-aarch64.zip`, with rebuilt
packages in `output/`. Never label a script, an empty zip or a system-shell
starter as that output.

## CI workflow

[`.github/workflows/bootstrap.yml`](../../.github/workflows/bootstrap.yml)
runs the same procedure on GitHub-hosted `ubuntu-24.04` runners. It runs on
`workflow_dispatch`, or on a pull request when the `bootstrap` label is added.
Adding other labels does nothing and does not cancel a running build. Its
matrix is `aarch64` and `x86_64`; `arm` and `i686` are not built in CI. Each
job has a 350-minute timeout and read-only `contents` permission, and does the
following:

1. Frees runner disk space (`/usr/share/dotnet`, `/opt/ghc`,
   `/usr/local/lib/android` and similar). It uses the second runner disk
   (`/mnt`) for the package build trees when it is mounted.
2. Runs the bootstrap tooling unit tests.
3. Runs `bootstrap.py prepare` and `preflight` on a fresh checkout.
4. Builds the builder image from `tools/bootstrap/Dockerfile`. No proxy secret
   is passed.
5. Runs `verify-source`, then `build-bootstraps.sh $(build-args --arch <arch>)`
   with `TERMUX_INSTALL_DEPS=false` in the container.
6. Fails if the log shows downloaded target packages or circular-dependency
   fallbacks.
7. Runs `verify-artifact` and writes `bootstrap-<arch>.zip.sha256`.
8. Uploads `bootstrap-<arch>.zip`, its SHA-256, the build log, the verifier
   output and a provenance record as the artifact
   `commandroid-line-bootstrap-<arch>-<sha>`, kept for 30 days. The provenance
   record holds the commit, source revision, image ID and prepared source diff.
   A failed or timed-out build uploads its log instead.

When both architectures built and verified, the `apk` job calls
[`.github/workflows/bootstrap-apk.yml`](../../.github/workflows/bootstrap-apk.yml),
which is also runnable by hand (`workflow_dispatch`, input `bootstrap_run_id`)
once it is on the default branch. It:

1. Downloads the run's bootstrap artifacts, checks each recorded SHA-256 and
   runs `verify-artifact` again with this commit's tools.
2. Builds `:app:assembleDebug -PcommandroidBootstrapDir=<aarch64 dir>`. It
   checks that the APK holds `assets/bootstrap/bootstrap-aarch64.zip` and no
   x86_64 archive, then uploads
   `commandroid-line-debug-bootstrap-aarch64-<sha>` (APK, its SHA-256 and the
   verifier output, kept 30 days).
3. On API 34 and API 36 x86_64 emulators, installs a build that bundles the
   x86_64 archive. It starts the app and waits for `$PREFIX/bin/bash`
   ([`tools/ci/emulator-bootstrap-install.sh`](../../tools/ci/emulator-bootstrap-install.sh)),
   records the `bash`, `node`, `npm` and `git` versions, and then runs
   `:app:connectedDebugAndroidTest`. This is the only place
   `InteractiveReadinessTest.compatPrefixBashAndNodeSeeAnInteractiveTerminal`
   runs instead of skipping.

None of this job has run yet: no bootstrap build has succeeded.

GitHub's default runners may not fit a cold 120-package build within the job
limit. Node.js/V8 in particular can take a long time on a few cores. Timing on
GitHub has not been measured. If the limit is hit, a larger runner or a
self-hosted runner is the next step. Do not try to save time by downloading
official packages. Never commit artifacts to this repository. Attach release
copies to a GitHub Release.

## Verification and app handoff

```sh
python3 tools/bootstrap/bootstrap.py verify-artifact \
  "$COMMANDROID_SOURCE/bootstrap-aarch64.zip" --arch aarch64
```

The validator checks:

- relative, unique paths and safe symlink destinations
- essential files and ELF machine types
- custom-prefix evidence in bash, apt and dpkg
- the second-stage configuration and disabled apt sources

It also rejects:

- the private prefix of `com.termux` or `com.neoterm` (under `/data/data/` or
  `/data/user/<n>/`) in runtime files (`bin/`, `lib/`, `libexec/`, `etc/`,
  `var/lib/dpkg/`) and in symlink targets
- any shipped `etc/termux/mirrors` definition or `chosen_mirrors` link
- an archive in which a configured extra package or its executable is missing

Use `--without-extra-packages` only for an archive built that way. The
validator prints the archive's SHA-256 only after these static checks pass. It
cannot prove that anything executes, and it cannot authenticate who ran the
build. Keep the source/configuration record and the complete build logs
separately.

The app loads the bootstrap from the APK asset `bootstrap/bootstrap-<arch>.zip`,
for `<arch>` in `aarch64`, `arm`, `i686` and `x86_64`. Supply the files at
build time with the Gradle property
`-PcommandroidBootstrapDir=<directory containing bootstrap-<arch>.zip>`. Only
pass validated archives built by this procedure.

Before shipping an embedded bootstrap, validate the following on clean devices,
with Termux also installed:

- installation
- bash and external-command execution
- PTY, signals and UTF-8
- completion of the second-stage postinst scripts
- `node`, `npm` and `git`
- npm installation of the agent CLIs
- package operations against a rebuilt commandroid-line repository

Run that matrix for every distributed ABI. An unbuilt ABI must never fall back
to an official Termux bootstrap. Corresponding source and component licenses
must accompany any binary distribution.

## Scope and measured constraints

The earlier workspace measurement (2026-10-29, under the NeoTerm identity)
found:

- a reachable local Docker 28.4.0 daemon with the `vfs` storage driver and no
  cached images
- `/dev/fuse`, 5 CPUs and about 22 GiB of free disk

No builder image, container or full bootstrap was run then or for this
retargeting. `vfs` duplicates layers, and the package toolchain copies the NDK
for API 24 and API 28, so that workspace has a substantial risk of running out
of disk. Reserve at least 40 GiB on a normal builder, and more for `vfs`. These
are planning allowances, not measured peaks.

The pinned resolver reports 39 prerequisites for bash alone, because bash
depends on the broad termux-tools set. apt has 81 source-build prerequisites,
including Python/Tk/X11, Perl, GnuPG, Tor and documentation tools. Some of these
come from build dependencies or sibling subpackages rather than apt's runtime
needs. Reproduce individual lists with:

```sh
cd "$COMMANDROID_SOURCE"
python3 scripts/buildorder.py packages/bash
python3 scripts/buildorder.py packages/nodejs-lts
```

Expect a cold full source bootstrap to take hours per ABI, and longer with
downloads or build failures. No elapsed build time has been measured. A smaller
bootstrap would need an explicitly audited package-set variant: removing
dependency files does not preserve the standard bootstrap's behaviour.
