# Building a genuine NeoTerm bootstrap

NeoTerm's application ID is `com.neoterm`; its native package prefix is
`/data/data/com.neoterm/files/usr`. Official Termux `.deb` files and bootstrap
archives are compiled for another app's private directory. They must not be
reused, byte-patched, or installed from official Termux repositories in NeoTerm.

This repository includes **source preparation and static validation tooling**.
It does not contain or claim a completed custom-prefix bootstrap. Building the
source and testing on Android remain separate release requirements. A system
shell can provide a standalone terminal while that work is pending; it does not
provide apt, Python, Node, a Linux distribution, or agent compatibility.

## Pinned source and configuration

[config.json](../../tools/bootstrap/config.json) pins termux-packages commit
`5a6d1c1eb868795dce83a6c269387b9f82d21805`, tag
`bootstrap-2025.03.28-r1+apt-android-7`. This is the bootstrap revision selected by
upstream termux-app v0.118.3. It retains the app's expected second-stage entry
point, `etc/termux/bootstrap/termux-bootstrap-second-stage.sh`. Current packages
master has changed that location and its toolchain; it is not a drop-in build
dependency for this app.

The upstream `scripts/build-bootstraps.sh` header explicitly describes building
from local sources for forked Termux apps without first publishing an apt repo.
Its `--architectures` option accepts `aarch64`, `arm`, `i686`, and `x86_64`.
The package identity is configured in `scripts/properties.sh`, not a bootstrap
command-line option. That file assigns `TERMUX_APP__PACKAGE_NAME` directly, so
exporting an environment variable alone does not change the build prefix.

Preparation makes these narrowly scoped source changes:

- Set `TERMUX_APP__PACKAGE_NAME=com.neoterm` and `TERMUX_APP__NAME=NeoTerm`.
  The rootfs, prefix, and home derive as `/data/data/com.neoterm/files`,
  `/data/data/com.neoterm/files/usr`, and `/data/data/com.neoterm/files/home`.
  Keep `TERMUX_APP__NAMESPACE=com.termux` because the existing Java classes retain
  their namespace. Keep internal Termux environment-variable names compatible.
- Generate a commented, empty apt `sources.list`. A rebuilt, signed NeoTerm
  package repository is still needed for `pkg install` and upgrades. Utilities
  such as `termux-change-repo` must not be used to enable official mirrors.
- Correct two local-source bootstrap wrapper omissions in this revision:
  explicitly export `TERMUX_PACKAGE_MANAGER=apt`, and pass `TERMUX_ARCH` instead
  of the undefined `package_arch` to second-stage generation. Otherwise the
  second-stage package-manager/architecture substitutions can be empty.

Every change must exactly match its expected pinned upstream source. The tool
checks the resulting effective properties by sourcing the actual configuration.
It rejects a different source revision, configuration drift, unexpected tracked
changes, and an output directory containing old `.deb` files.

## Preparation and preflight

Run from this repository, using a new dedicated checkout outside it:

```sh
export NEOTERM_SOURCE=/workspace/scratch/neoterm-bootstrap
python3 tools/bootstrap/bootstrap.py prepare "$NEOTERM_SOURCE"
python3 tools/bootstrap/bootstrap.py preflight "$NEOTERM_SOURCE" --docker
```

These commands clone/prepare source and make a read-only local Docker daemon
check. They do not start a container, install build dependencies, or compile.
`prepare` refuses an existing destination instead of resetting somebody's work.
Use a fresh checkout and dedicated build container for each architecture.

For a host or already provisioned builder, preflight can additionally check its
local toolchain:

```sh
export NDK=/path/to/android-ndk-r27c
export TERMUX_JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
python3 tools/bootstrap/bootstrap.py preflight "$NEOTERM_SOURCE" --build-env
```

The package build requires **NDK r27c**, numeric revision `27.2.12479018`, and
**JDK 17**. The setup script verifies the NDK archive SHA-256
`59c2f6dc96743b5daf5d1626684640b20a6bd2b1d85b13156b90333741bad5cc`.
The app's existing NDK r22 and JDK 11 are separate build tools and do not satisfy
this package-build configuration. The pinned setup also selects SDK command
tools revision 9123335 and Android build-tools 33.0.1.

## Full source build

Provision a Linux amd64 machine with adequate disk before running these steps.
The supplied [Dockerfile](../../tools/bootstrap/Dockerfile) derives from the
pinned upstream builder, uses Ubuntu 24.04, and installs its extensive build
dependencies plus the correct SDK/NDK/JDK. Do not pull the moving
`ghcr.io/termux/package-builder:latest`: its current toolchain targets newer
package sources. The base tag and apt indices are live; record the final image
ID/digest, package versions, source diff, and checksums with release artifacts.
This is a pinned source procedure, not a claim of bit-for-bit reproducibility.

In a managed cloud environment, preserve configured Docker proxy settings and
pass the session CA as a BuildKit secret. The Dockerfile temporarily installs it
within one build step, then removes it before committing the image layer.
`CODEX_PROXY_CERT` is supplied by that environment; ordinary builders can omit
the `--secret` argument when no extra trust certificate is required.

```sh
docker build --platform=linux/amd64 \
  --secret id=proxy_ca,src="$CODEX_PROXY_CERT" \
  --file tools/bootstrap/Dockerfile \
  --tag neoterm-package-builder:termux-5a6d1c1e \
  "$NEOTERM_SOURCE/scripts"
docker image inspect --format '{{.Id}}' neoterm-package-builder:termux-5a6d1c1e
```

Before compiling, run source preflight again. The following managed-environment
example mounts the session CA read-only, installs trust only in the disposable
container, makes the dedicated source checkout writable by the builder, and
builds one ABI from sources. It changes ownership of that dedicated checkout to
UID/GID 1001. Use a different checkout for the next ABI. Docker client proxy
defaults must contain a proxy address reachable from inner containers.

```sh
python3 tools/bootstrap/bootstrap.py verify-source "$NEOTERM_SOURCE"
docker run --rm --platform=linux/amd64 --user root \
  --security-opt seccomp=unconfined --cap-add SYS_ADMIN --device /dev/fuse \
  --mount "type=bind,src=$NEOTERM_SOURCE,dst=/home/builder/termux-packages" \
  --mount "type=bind,src=$CODEX_PROXY_CERT,dst=/run/proxy-ca.crt,readonly" \
  neoterm-package-builder:termux-5a6d1c1e \
  bash -ec 'install -m 0644 /run/proxy-ca.crt /usr/local/share/ca-certificates/neoterm-session-proxy.crt
    update-ca-certificates
    chown -R builder:builder /home/builder/termux-packages
    exec su --whitelist-environment=HTTP_PROXY,HTTPS_PROXY,NO_PROXY,http_proxy,https_proxy,no_proxy - builder -c \
      "cd /home/builder/termux-packages && TERMUX_INSTALL_DEPS=false TERMUX_PKG_MAKE_PROCESSES=4 ./scripts/build-bootstraps.sh --architectures aarch64"'
```

The capabilities reflect the upstream builder's proot/fuse requirements. Where
available, use upstream's seccomp/AppArmor profiles instead of the generic
seccomp setting shown above. Do not use host networking to work around egress
policy. On ordinary builders without a session CA, omit the CA mount and initial
certificate installation. Leave TLS verification enabled.

Use the default Android 7+ **apt** bootstrap mode. The script's `--android10`
option selects a different APK package-distribution system; it is not required
merely because a device runs Android 10 or newer. Do not pass dependency download
options `-i`/`-I`: they may introduce official-prefix target packages. Audit the
build log for circular-dependency fallback downloads as well; source build
support does not make any downloaded target `.deb` compatible.

The expected real output is `$NEOTERM_SOURCE/bootstrap-aarch64.zip`, with rebuilt
packages in `output/`. Never label a script, empty zip, or a system-shell starter
as that output. Additional packages can be requested using upstream's
`--add openssh,proot`, but Python, Node, proot-distro, and individual agents need
their own rebuilt dependency closures and Android execution validation.

## Verification and app handoff

```sh
python3 tools/bootstrap/bootstrap.py verify-artifact \
  "$NEOTERM_SOURCE/bootstrap-aarch64.zip" --arch aarch64
```

The validator checks relative/unique paths, safe symlink destinations, essential
files, ELF machine types, custom-prefix evidence in bash/apt/dpkg, second-stage
configuration, and disabled apt sources. It rejects the old private prefix in
runtime files and emits the real archive's SHA-256 only after those static checks
pass. This cannot prove successful execution or authenticate who ran the build;
retain the source/configuration record and complete build logs separately.

Before changing app bootstrap embedding/download pins, validate clean-device
installation with Termux also installed, bash and external-command execution,
PTY/signals/UTF-8, second-stage postinst completion, and then package operations
against a rebuilt NeoTerm repository. Run that matrix for every distributed ABI.
An unbuilt ABI must not fall back to an official Termux bootstrap. Corresponding
source and component licenses must accompany any binary distribution.

## Scope and measured constraints

Research on 2026-10-29 confirmed a reachable local Docker 28.4.0 daemon, the
`vfs` storage driver, zero cached images, `/dev/fuse`, 5 CPUs, and about 22 GiB
free disk. No builder image/container or full bootstrap was run for this work.
The full package builder is not present. `vfs` duplicates layers, and the package
toolchain copies the NDK for API 24 and API 28, so this workspace has a substantial
disk-exhaustion risk. Reserve at least 40 GiB on a normal builder and additional
space for `vfs`; these are planning allowances, not measured peak requirements.

The pinned source's dependency resolver reports 39 prerequisites for bash alone
because bash depends on the broad termux-tools set. Apt has 81 source-build
prerequisites, including Python/Tk/X11, Perl, GNUPG, Tor, and documentation tools;
some arise from build dependencies or sibling subpackages rather than apt's
runtime needs. The default bootstrap requests 30 top-level packages; the same
resolver yields 111 distinct source package builds for their combined aarch64
closure. Reproduce individual prerequisite lists with:

```sh
cd "$NEOTERM_SOURCE"
python3 scripts/buildorder.py packages/bash
python3 scripts/buildorder.py packages/apt
```

Expect a cold full source bootstrap to take hours per ABI, potentially longer
with downloads or build failures. No elapsed build time was benchmarked here.
A smaller bootstrap would require an explicitly audited package-set variant;
simply removing dependency files does not preserve the standard bootstrap's
behavior. A source-built proot plus a verified Linux rootfs is another viable
future route to Linux agents, with its own integration and device tests. None of
these pending paths should be advertised as available in the system-shell build.
