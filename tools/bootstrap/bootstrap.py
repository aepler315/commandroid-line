#!/usr/bin/env python3
"""Prepare pinned commandroid-line package sources and inspect real bootstrap outputs.

This tool never starts containers, compiles packages, or produces bootstrap zips.
See docs/neoterm/BOOTSTRAP-BUILD.md for the separate source build procedure.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import posixpath
import re
import shlex
import shutil
import struct
import subprocess
import sys
import zipfile

CONFIG_PATH = Path(__file__).with_name("config.json")
MARKER = ".commandroid-line-bootstrap-source.json"
BASE_CHANGED_FILES = ("scripts/properties.sh", "packages/apt/build.sh", "packages/termux-tools/build.sh",
                      "scripts/build-bootstraps.sh")
# Re-pins replace a pinned source checksum only where an on-the-fly archive was regenerated
# with identical contents; each entry in config.json records how that was verified.
REPIN_FILE = re.compile(r"(?:packages|x11-packages|root-packages)/[a-z0-9][a-z0-9+.-]*/build\.sh")
SHA256 = re.compile(r"[0-9a-f]{64}")
# URL rewrites only move a download; the pinned checksum still verifies the content.
URL_PREFIX = re.compile(r"https://[A-Za-z0-9.-]+(?:/[A-Za-z0-9._~%+-]+)*/")
MACHINES = {"aarch64": 183, "arm": 40, "i686": 3, "x86_64": 62}
# An accidental edit must never silently select another app's sandbox.
IDENTITY = {
    "package_name": "com.commandroid_line", "app_namespace": "com.termux", "app_name": "commandroid-line",
    "data_dir": "/data/data/com.commandroid_line", "rootfs": "/data/data/com.commandroid_line/files",
    "prefix": "/data/data/com.commandroid_line/files/usr", "home": "/data/data/com.commandroid_line/files/home",
    "package_manager": "apt",
}
# Official Termux and the earlier NeoTerm identity: binaries carrying either private
# prefix are compiled for another app's sandbox and must never enter this bootstrap.
FOREIGN_PREFIX = re.compile(rb"(?:/data/data|/data/user/[0-9]+)/(com\.(?:termux|neoterm))(?![A-Za-z0-9_.])")
PACKAGE_NAME = re.compile(r"[a-z0-9][a-z0-9+.-]*")
# Executables that must exist when an extra package is requested.
EXTRA_EXECUTABLES = {"nodejs-lts": ("bin/node",), "git": ("bin/git",)}
# Inserted into build-bootstraps.sh build_package() (see prepared_file).
SUBPACKAGE_PARENT_SNIPPET = """\tlocal package_name="$2"

\t# commandroid-line: build a subpackage-only name (bzip2) through its parent (libbz2).
\tlocal package_dir parent_dir
\tif ! compgen -G "$TERMUX_PACKAGES_DIRECTORY/*packages/$package_name/build.sh" >/dev/null; then
\t\tfor package_dir in packages root-packages x11-packages; do
\t\t\tparent_dir="$(find "$TERMUX_PACKAGES_DIRECTORY/$package_dir" -mindepth 2 -maxdepth 2 \\
\t\t\t\t-name "$package_name.subpackage.sh" -printf '%h\\n' 2>/dev/null | head -n 1)"
\t\t\tif [ -n "$parent_dir" ]; then
\t\t\t\techo "[*] '$package_name' is a subpackage of '$(basename "$parent_dir")'; building that"
\t\t\t\tpackage_name="$(basename "$parent_dir")"
\t\t\t\tbreak
\t\t\tfi
\t\tdone
\tfi
"""
RUNTIME_DIRS = ("bin/", "lib/", "libexec/", "etc/", "var/lib/dpkg/")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def run(args, **kwargs):
    return subprocess.run(args, check=True, text=True, capture_output=True, **kwargs).stdout.strip()


def configuration():
    config = json.loads(CONFIG_PATH.read_text())
    for key, expected in IDENTITY.items():
        require(config.get(key) == expected, f"Unsafe {key} in {CONFIG_PATH}; expected {expected}")
    require(re.fullmatch(r"[0-9a-f]{40}", config["source_revision"]), "Invalid pinned source revision")
    extras = config.get("extra_packages", [])
    require(isinstance(extras, list) and all(isinstance(name, str) and PACKAGE_NAME.fullmatch(name)
                                             for name in extras), "extra_packages must be termux package names")
    require(len(extras) == len(set(extras)), "Duplicate extra_packages entry")
    config["extra_packages"] = extras
    repins = config.get("checksum_repins", [])
    require(isinstance(repins, list), "checksum_repins must be a list")
    for repin in repins:
        require(isinstance(repin, dict) and set(repin) == {"file", "url", "old", "new", "verified"},
                "checksum_repins entries need exactly file, url, old, new and verified")
        require(REPIN_FILE.fullmatch(repin["file"]) is not None, f"Unsafe checksum_repins file: {repin['file']}")
        require(SHA256.fullmatch(repin["old"]) and SHA256.fullmatch(repin["new"]) and repin["old"] != repin["new"],
                "checksum_repins old/new must be distinct SHA-256 digests")
        require(repin["file"] not in BASE_CHANGED_FILES, "checksum_repins cannot target an identity-edited file")
        require(isinstance(repin["verified"], str) and len(repin["verified"]) >= 20,
                "checksum_repins entries must record how the new archive was verified")
    config["checksum_repins"] = repins
    rewrites = config.get("source_url_rewrites", [])
    require(isinstance(rewrites, list), "source_url_rewrites must be a list")
    for rewrite in rewrites:
        require(isinstance(rewrite, dict) and set(rewrite) == {"file", "old", "new", "count", "verified"},
                "source_url_rewrites entries need exactly file, old, new, count and verified")
        require(REPIN_FILE.fullmatch(rewrite["file"]) is not None, f"Unsafe source_url_rewrites file: {rewrite['file']}")
        require(rewrite["file"] not in BASE_CHANGED_FILES, "source_url_rewrites cannot target an identity-edited file")
        require(all(isinstance(rewrite[k], str) and URL_PREFIX.fullmatch(rewrite[k]) for k in ("old", "new"))
                and rewrite["old"] != rewrite["new"], "source_url_rewrites old/new must be distinct https URL prefixes")
        require(isinstance(rewrite["count"], int) and rewrite["count"] >= 1, "source_url_rewrites count must be >= 1")
        require(isinstance(rewrite["verified"], str) and len(rewrite["verified"]) >= 20,
                "source_url_rewrites entries must record how the new location was verified")
    config["source_url_rewrites"] = rewrites
    return config


def changed_files(config):
    edited = {repin["file"] for repin in config["checksum_repins"]}
    edited |= {rewrite["file"] for rewrite in config["source_url_rewrites"]}
    return BASE_CHANGED_FILES + tuple(sorted(edited))


def replace_once(text, old, new):
    require(text.count(old) == 1, f"Pinned source differs: expected one occurrence of {old!r}")
    return text.replace(old, new, 1)


def prepared_file(source, relative, config):
    original = run(["git", "-C", str(source), "show", f'{config["source_revision"]}:{relative}']) + "\n"
    if relative == "scripts/properties.sh":
        original = replace_once(original, 'TERMUX_APP__PACKAGE_NAME="com.termux"',
                                f'TERMUX_APP__PACKAGE_NAME="{config["package_name"]}"')
        original = replace_once(original, 'TERMUX_APP__NAME="$TERMUX__NAME"',
                                f'TERMUX_APP__NAME="{config["app_name"]}"')
        require(f'TERMUX_APP__NAMESPACE="{config["app_namespace"]}"' in original,
                "Java namespace and Android application ID must be configured separately")
    elif relative == "packages/apt/build.sh":
        old = '''\t{
\t\techo "# The main termux repository, with cloudflare cache"
\t\techo "deb https://packages-cf.termux.dev/apt/termux-main/ stable main"
\t\techo "# The main termux repository, without cloudflare cache"
\t\techo "# deb https://packages.termux.dev/apt/termux-main/ stable main"
\t} > $TERMUX_PREFIX/etc/apt/sources.list'''
        new = '''\t# commandroid-line requires packages compiled for its own private prefix.
\tprintf '%s\\n' '# commandroid-line: no package repository is configured.' \\
\t\t'# Do not enable official Termux repositories: their binary prefix is incompatible.' \\
\t\t> "$TERMUX_PREFIX/etc/apt/sources.list"'''
        original = replace_once(original, old, new)
    elif relative == "packages/termux-tools/build.sh":
        # pkg's select_mirror rewrites an empty sources.list with a randomly chosen
        # official Termux mirror from etc/termux/mirrors; ship no mirror definitions.
        old = '''termux_step_post_make_install() {
\tTERMUX_PKG_CONFFILES="$(cat "$TERMUX_PKG_BUILDDIR/conffiles")"
}'''
        new = '''termux_step_post_make_install() {
\t# commandroid-line: official Termux mirrors serve binaries for another prefix.
\trm -rf "$TERMUX_PREFIX/etc/termux/mirrors"
\tTERMUX_PKG_CONFFILES="$(grep -v '^etc/termux/mirrors/' "$TERMUX_PKG_BUILDDIR/conffiles" || true)"
}'''
        original = replace_once(original, old, new)
    elif relative != "scripts/build-bootstraps.sh":
        repins = [repin for repin in config["checksum_repins"] if repin["file"] == relative]
        rewrites = [rewrite for rewrite in config["source_url_rewrites"] if rewrite["file"] == relative]
        require(repins or rewrites, f"No source edit is defined for {relative}")
        for repin in repins:
            # Only the exact pinned digest is replaced; any other upstream drift is refused.
            original = replace_once(original, repin["old"], repin["new"])
        for rewrite in rewrites:
            found = original.count(rewrite["old"])
            require(found == rewrite["count"],
                    f"Pinned source differs: expected {rewrite['count']} occurrences of {rewrite['old']!r} in {relative}, found {found}")
            original = original.replace(rewrite["old"], rewrite["new"])
    else:
        # Upstream's local-source builder otherwise leaves both substitutions empty:
        # the package-manager variable is set only in child build-package processes,
        # and package_arch is not a variable of main().
        original = replace_once(original, "\nset -e\n\nexport TERMUX_SCRIPTDIR",
                                "\nset -e\n\nexport TERMUX_PACKAGE_MANAGER=apt\n\nexport TERMUX_SCRIPTDIR")
        original = replace_once(original, 'add_termux_bootstrap_second_stage_files "$package_arch"',
                                'add_termux_bootstrap_second_stage_files "$TERMUX_ARCH"')
        # The default package list names bzip2, which is only a subpackage of libbz2. This
        # revision's build-package.sh then fails with "No package bzip2 found in any of the
        # enabled repositories", which build_package's "No build.sh script" exception does not
        # match; upstream's usual download-based bootstrap flow never reaches it. Build such a
        # name through the package whose *.subpackage.sh produces it; build-package.sh skips a
        # parent that an earlier dependency build already produced.
        original = replace_once(original, '\tlocal package_name="$2"\n', SUBPACKAGE_PARENT_SNIPPET)
    return original


def marker_contents(config):
    return json.dumps({"source_revision": config["source_revision"],
                       "config_sha256": hashlib.sha256(CONFIG_PATH.read_bytes()).hexdigest()}, indent=2) + "\n"


def verify_source(source, config):
    require(source.is_dir(), f"Missing source checkout: {source}")
    require(run(["git", "-C", str(source), "rev-parse", "HEAD"]) == config["source_revision"],
            "Source HEAD is not the pinned bootstrap revision")
    require((source / MARKER).is_file() and (source / MARKER).read_text() == marker_contents(config),
            "Missing or stale preparation marker; use a fresh checkout with prepare")
    changed = set(run(["git", "-C", str(source), "diff", "--name-only", "HEAD"]).splitlines())
    require(changed == set(changed_files(config)), f"Unexpected tracked source modifications: {sorted(changed)}")
    for relative in changed_files(config):
        require((source / relative).read_text() == prepared_file(source, relative, config),
                f"Source configuration drift in {relative}")
    # Source the actual properties script; checking only the configured JSON is insufficient.
    values = run(["bash", "-c", '. "$1"; printf "%s\\n" "$TERMUX_APP_PACKAGE" "$TERMUX_APP__DATA_DIR" '
                  '"$TERMUX__ROOTFS" "$TERMUX_PREFIX" "$TERMUX_ANDROID_HOME" "$TERMUX_APP__NAMESPACE"',
                  "commandroid-line-verify", str(source / "scripts/properties.sh")]).splitlines()
    expected = [config[key] for key in ("package_name", "data_dir", "rootfs", "prefix", "home", "app_namespace")]
    require(values == expected, f"Effective source properties disagree with commandroid-line configuration: {values}")
    # build-bootstraps.sh ignores "No build.sh script" failures, so a misspelled or
    # subpackage-only --add name would silently be left out of the archive.
    for name in config["extra_packages"]:
        require((source / "packages" / name / "build.sh").is_file(),
                f"Extra package {name} has no packages/{name}/build.sh in the pinned source")
    # Running in a previously used tree can mix foreign-prefix debs into the zip.
    require(not (source / "output").exists() or not any((source / "output").glob("*.deb")),
            "Source output/ already contains .debs; use a fresh dedicated build checkout")
    print(f"Source verified: {config['source_revision']} for {config['prefix']}")


def prepare(source, config):
    require(not source.exists(), "prepare requires a new destination; it never resets an existing checkout")
    source.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(["git", "clone", "--depth", "1", "--branch", config["source_tag"],
                    config["source_url"], str(source)], check=True)
    require(run(["git", "-C", str(source), "rev-parse", "HEAD"]) == config["source_revision"],
            "Remote tag no longer matches the pinned revision; refusing to prepare")
    for relative in changed_files(config):
        (source / relative).write_text(prepared_file(source, relative, config))
    (source / MARKER).write_text(marker_contents(config))
    verify_source(source, config)
    print("Prepared source only. No packages, container, or bootstrap archive were built.")


def preflight(source, config, check_docker, build_env):
    verify_source(source, config)
    free = shutil.disk_usage(source).free // (1024 ** 3)
    print(f"Available source-filesystem space: {free} GiB (40+ GiB recommended for a first full build)")
    if free < 40:
        print("Resource risk: full builder, copied NDK toolchains, package source/build trees may exhaust this disk.")
    if check_docker:
        env = os.environ.copy()
        for key in ("DOCKER_HOST", "DOCKER_CONTEXT", "DOCKER_TLS", "DOCKER_TLS_VERIFY", "DOCKER_CERT_PATH"):
            env.pop(key, None)
        info = run(["docker", "--host=unix:///var/run/docker.sock", "info", "--format",
                    "Server={{.ServerVersion}} Driver={{.Driver}} Images={{.Images}}"], env=env)
        print(f"Local daemon: {info}")
        if "Driver=vfs" in info:
            print("Resource risk: vfs duplicates image layers; reserve additional builder disk space.")
    if build_env:
        ndk = Path(os.environ.get("NDK", "/missing-ndk"))
        require((ndk / "source.properties").is_file(), "Set NDK to an installed Android NDK r27c")
        require(re.search(r"^Pkg\.Revision\s*=\s*" + re.escape(config["ndk_revision"]) + r"\s*$",
                          (ndk / "source.properties").read_text(), re.M), "Build requires exact NDK r27c")
        java_home = Path(os.environ.get("TERMUX_JAVA_HOME", "/usr/lib/jvm/java-17-openjdk-amd64"))
        result = subprocess.run([str(java_home / "bin/java"), "-version"], text=True, capture_output=True, check=True)
        require(re.search(r'version "17[.\"]', result.stderr + result.stdout), "Build requires JDK 17")
        print("NDK r27c and JDK 17 verified. Check the complete pinned builder dependencies before compiling.")


def build_args(arch, config, include_extra=True):
    """Arguments for scripts/build-bootstraps.sh; every value is validated to be shell-safe."""
    require(arch in MACHINES and arch in config["architectures"], f"Unsupported architecture {arch}")
    args = ["--architectures", arch]
    if include_extra and config["extra_packages"]:
        args += ["--add", ",".join(config["extra_packages"])]
    return args


def relative_path(name):
    return bool(name) and not name.startswith("/") and "\\" not in name and all(
        part not in ("", ".", "..") for part in name.rstrip("/").split("/"))


def symlink_destination(name):
    """SYMLINKS.txt destination relative to the prefix, or None if it is unsafe.

    build-bootstraps.sh writes each line from `find . -type l`, so destinations look like
    `./bin/sh`; exactly one leading './' is removed and everything else must be a plain
    relative path (no '..', '.', empty or absolute components).
    """
    if name.startswith("./"):
        name = name[2:]
    return name if relative_path(name) else None


def foreign_prefix(data):
    match = FOREIGN_PREFIX.search(data)
    return match.group(1).decode() if match else None


def installed_packages(status):
    return {line[len("Package:"):].strip() for line in status.decode(errors="replace").splitlines()
            if line.startswith("Package:")}


def verify_artifact(archive_path, arch, config, include_extra=True):
    require(archive_path.name == f"bootstrap-{arch}.zip", "Artifact filename must identify its architecture")
    prefix = config["prefix"].encode()
    extras = config["extra_packages"] if include_extra else []
    with zipfile.ZipFile(archive_path) as archive:
        names = archive.namelist()
        require(len(names) == len(set(names)), "Duplicate zip paths")
        require(all(relative_path(name) for name in names), "Unsafe or non-relative zip member path")
        require("SYMLINKS.txt" in names, "Bootstrap has no SYMLINKS.txt")
        links = {}
        for line in archive.read("SYMLINKS.txt").decode().splitlines():
            require(line.count("←") == 1, "Malformed bootstrap symlink entry")
            target, raw_name = line.split("←")
            require(target and "\\" not in target, "Empty or invalid symlink target")
            name = symlink_destination(raw_name)
            require(name is not None and name not in links and name not in names,
                    f"Unsafe or duplicate symlink destination: {raw_name}")
            normalized_target = posixpath.normpath(target)
            require(not target.startswith("/data/") or normalized_target.startswith(config["prefix"] + "/"),
                    f"Symlink escapes commandroid-line's private prefix: {target}")
            found = foreign_prefix(target.encode())
            require(found is None, f"Foreign private prefix {found} in symlink {name}")
            if not target.startswith("/"):
                # Resolve .. lexically; symlinks may refer to siblings but must stay inside the zip root.
                depth = len(PurePosixPath(name).parent.parts)
                for part in target.split("/"):
                    depth += -1 if part == ".." else (0 if part in ("", ".") else 1)
                    require(depth >= 0, f"Relative symlink escapes prefix: {name}")
            links[name] = target
        required = ("bin/bash", "bin/apt", "bin/dpkg", "bin/ls", "bin/sh", "etc/apt/sources.list",
                    "var/lib/dpkg/status", "etc/termux/bootstrap/termux-bootstrap-second-stage.sh")
        require(all(name in names or name in links for name in required), "Required full bootstrap files are missing")
        for member in archive.infolist():
            require((member.external_attr >> 16) & 0o170000 != 0o120000,
                    f"Zip contains an unsupported native symlink: {member.filename}")
            data = archive.read(member)
            if member.filename.startswith(RUNTIME_DIRS):
                found = foreign_prefix(data)
                require(found is None, f"Foreign private prefix {found} in {member.filename}")
            if data.startswith(b"\x7fELF"):
                require(len(data) >= 20 and data[5] == 1, f"Invalid ELF header: {member.filename}")
                require(struct.unpack_from("<H", data, 18)[0] == MACHINES[arch],
                        f"Wrong ELF architecture in {member.filename}")
        for binary in ("bin/bash", "bin/apt", "bin/dpkg"):
            require(archive.read(binary).startswith(b"\x7fELF"), f"Expected a compiled ELF executable at {binary}")
            require(prefix in archive.read(binary), f"Custom prefix evidence missing from {binary}")
        stage = archive.read("etc/termux/bootstrap/termux-bootstrap-second-stage.sh")
        require(stage.startswith(b"#!" + prefix + b"/bin/bash\n"), "Second-stage shebang has wrong prefix")
        require(b'export TERMUX_PACKAGE_MANAGER="apt"' in stage, "Second-stage package manager was not configured")
        require(f'export TERMUX_PACKAGE_ARCH="{arch}"'.encode() in stage, "Second-stage architecture was not configured")
        for name in names:
            if name == "etc/apt/sources.list" or name.startswith("etc/apt/sources.list.d/"):
                for line in archive.read(name).decode().splitlines():
                    require(not line.strip() or line.lstrip().startswith("#"),
                            f"Unexpected active apt source in {name}; repository handoff requires a separate review")
        # pkg would otherwise replace the disabled sources.list with an official mirror.
        for name in list(names) + list(links):
            require(not name.startswith("etc/termux/mirrors/") and name != "etc/termux/chosen_mirrors"
                    and not name.startswith("etc/termux/chosen_mirrors/"),
                    f"Official Termux mirror definition shipped: {name}")
        installed = installed_packages(archive.read("var/lib/dpkg/status"))
        for package in extras:
            require(package in installed, f"Requested extra package {package} is not installed in dpkg status")
            for executable in EXTRA_EXECUTABLES.get(package, ()):
                require(executable in names, f"Extra package {package} lacks {executable}")
                require(archive.read(executable).startswith(b"\x7fELF"),
                        f"Expected a compiled ELF executable at {executable}")
    digest = hashlib.sha256(archive_path.read_bytes()).hexdigest()
    print(json.dumps({"artifact": str(archive_path), "sha256": digest, "architecture": arch,
                      "source_revision": config["source_revision"], "prefix": config["prefix"],
                      "extra_packages": extras,
                      "status": "static checks passed; Android execution remains unverified"}, indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    for command in ("prepare", "verify-source", "preflight"):
        item = sub.add_parser(command)
        item.add_argument("source", type=lambda value: Path(value).resolve())
        if command == "preflight":
            item.add_argument("--docker", action="store_true", help="Read-only managed local daemon check")
            item.add_argument("--build-env", action="store_true", help="Also require local NDK r27c and JDK17")
    item = sub.add_parser("build-args", help="Print scripts/build-bootstraps.sh arguments for one ABI")
    item.add_argument("--arch", required=True, choices=MACHINES)
    item.add_argument("--without-extra-packages", action="store_true", help="Build only upstream's default set")
    item = sub.add_parser("verify-artifact")
    item.add_argument("archive", type=lambda value: Path(value).resolve())
    item.add_argument("--arch", required=True, choices=MACHINES)
    item.add_argument("--without-extra-packages", action="store_true",
                      help="Validate a default-set archive built with build-args --without-extra-packages")
    args = parser.parse_args()
    config = configuration()
    if args.command == "prepare":
        prepare(args.source, config)
    elif args.command == "verify-source":
        verify_source(args.source, config)
    elif args.command == "preflight":
        preflight(args.source, config, args.docker, args.build_env)
    elif args.command == "build-args":
        print(shlex.join(build_args(args.arch, config, not args.without_extra_packages)))
    else:
        verify_artifact(args.archive, args.arch, config, not args.without_extra_packages)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError, zipfile.BadZipFile) as error:
        print(f"bootstrap: {error}", file=sys.stderr)
        sys.exit(1)
