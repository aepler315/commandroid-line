#!/usr/bin/env python3
"""Prepare pinned NeoTerm package sources and inspect real bootstrap outputs.

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
import shutil
import struct
import subprocess
import sys
import zipfile

CONFIG_PATH = Path(__file__).with_name("config.json")
MARKER = ".neoterm-bootstrap-source.json"
CHANGED_FILES = ("scripts/properties.sh", "packages/apt/build.sh", "scripts/build-bootstraps.sh")
MACHINES = {"aarch64": 183, "arm": 40, "i686": 3, "x86_64": 62}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def run(args, **kwargs):
    return subprocess.run(args, check=True, text=True, capture_output=True, **kwargs).stdout.strip()


def configuration():
    config = json.loads(CONFIG_PATH.read_text())
    # An accidental edit must never silently select official Termux's sandbox.
    for key, expected in {
        "package_name": "com.neoterm", "data_dir": "/data/data/com.neoterm",
        "rootfs": "/data/data/com.neoterm/files", "prefix": "/data/data/com.neoterm/files/usr",
        "home": "/data/data/com.neoterm/files/home", "package_manager": "apt",
    }.items():
        require(config.get(key) == expected, f"Unsafe {key} in {CONFIG_PATH}; expected {expected}")
    require(re.fullmatch(r"[0-9a-f]{40}", config["source_revision"]), "Invalid pinned source revision")
    return config


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
        new = '''\t# NeoTerm requires packages compiled for its own private prefix.
\tprintf '%s\\n' '# NeoTerm: no package repository is configured.' \\
\t\t'# Do not enable official Termux repositories: their binary prefix is incompatible.' \\
\t\t> "$TERMUX_PREFIX/etc/apt/sources.list"'''
        original = replace_once(original, old, new)
    else:
        # Upstream's local-source builder otherwise leaves both substitutions empty:
        # the package-manager variable is set only in child build-package processes,
        # and package_arch is not a variable of main().
        original = replace_once(original, "\nset -e\n\nexport TERMUX_SCRIPTDIR",
                                "\nset -e\n\nexport TERMUX_PACKAGE_MANAGER=apt\n\nexport TERMUX_SCRIPTDIR")
        original = replace_once(original, 'add_termux_bootstrap_second_stage_files "$package_arch"',
                                'add_termux_bootstrap_second_stage_files "$TERMUX_ARCH"')
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
    require(changed == set(CHANGED_FILES), f"Unexpected tracked source modifications: {sorted(changed)}")
    for relative in CHANGED_FILES:
        require((source / relative).read_text() == prepared_file(source, relative, config),
                f"Source configuration drift in {relative}")
    # Source the actual properties script; checking only the configured JSON is insufficient.
    values = run(["bash", "-c", '. "$1"; printf "%s\\n" "$TERMUX_APP_PACKAGE" "$TERMUX_APP__DATA_DIR" '
                  '"$TERMUX__ROOTFS" "$TERMUX_PREFIX" "$TERMUX_ANDROID_HOME" "$TERMUX_APP__NAMESPACE"',
                  "neoterm-verify", str(source / "scripts/properties.sh")]).splitlines()
    expected = [config[key] for key in ("package_name", "data_dir", "rootfs", "prefix", "home", "app_namespace")]
    require(values == expected, f"Effective source properties disagree with NeoTerm configuration: {values}")
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
    for relative in CHANGED_FILES:
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


def relative_path(name):
    return bool(name) and not name.startswith("/") and "\\" not in name and all(
        part not in ("", ".", "..") for part in name.rstrip("/").split("/"))


def verify_artifact(archive_path, arch, config):
    require(archive_path.name == f"bootstrap-{arch}.zip", "Artifact filename must identify its architecture")
    old = b"/data/data/com.termux/"
    prefix = config["prefix"].encode()
    with zipfile.ZipFile(archive_path) as archive:
        names = archive.namelist()
        require(len(names) == len(set(names)), "Duplicate zip paths")
        require(all(relative_path(name) for name in names), "Unsafe or non-relative zip member path")
        require("SYMLINKS.txt" in names, "Bootstrap has no SYMLINKS.txt")
        links = {}
        for line in archive.read("SYMLINKS.txt").decode().splitlines():
            require(line.count("←") == 1, "Malformed bootstrap symlink entry")
            target, name = line.split("←")
            require(target and "\\" not in target, "Empty or invalid symlink target")
            require(relative_path(name) and name not in links and name not in names,
                    f"Unsafe or duplicate symlink destination: {name}")
            normalized_target = posixpath.normpath(target)
            require(not target.startswith("/data/") or normalized_target.startswith(config["prefix"] + "/"),
                    f"Symlink escapes NeoTerm's private prefix: {target}")
            require(old.decode() not in target, "Official Termux prefix in symlink")
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
            runtime = member.filename.startswith(("bin/", "lib/", "libexec/", "etc/", "var/lib/dpkg/"))
            require(not runtime or old not in data, f"Official Termux private prefix in {member.filename}")
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
    digest = hashlib.sha256(archive_path.read_bytes()).hexdigest()
    print(json.dumps({"artifact": str(archive_path), "sha256": digest, "architecture": arch,
                      "source_revision": config["source_revision"], "prefix": config["prefix"],
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
    item = sub.add_parser("verify-artifact")
    item.add_argument("archive", type=lambda value: Path(value).resolve())
    item.add_argument("--arch", required=True, choices=MACHINES)
    args = parser.parse_args()
    config = configuration()
    if args.command == "prepare":
        prepare(args.source, config)
    elif args.command == "verify-source":
        verify_source(args.source, config)
    elif args.command == "preflight":
        preflight(args.source, config, args.docker, args.build_env)
    else:
        verify_artifact(args.archive, args.arch, config)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError, zipfile.BadZipFile) as error:
        print(f"bootstrap: {error}", file=sys.stderr)
        sys.exit(1)
