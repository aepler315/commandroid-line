#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
# CI helper: install a debug build that bundles a bootstrap on a running emulator, start it so
# AppRuntime installs the bootstrap into /data/data/com.commandroid_line/files/usr, and wait for
# that to finish before connected tests run. Prints bash/node/git versions from the prefix.
# Usage: tools/ci/emulator-bootstrap-install.sh <dir with bootstrap-x86_64.zip> [timeout seconds]
set -euo pipefail
dir="${1:?bootstrap directory}"
timeout="${2:-900}"
pkg=com.commandroid_line
./gradlew --no-daemon --max-workers=2 :app:installDebug -PcommandroidBootstrapDir="$dir"
adb shell am start -W -n "$pkg/com.termux.app.TermuxActivity"
# BootstrapInstaller renames the staging prefix into place before the second stage runs.
waited=0
until adb shell run-as "$pkg" test -x files/usr/bin/bash; do
    if [ "$waited" -ge "$timeout" ]; then
        echo "bootstrap prefix did not appear within ${timeout}s" >&2
        adb shell run-as "$pkg" ls -la files files/usr 2>&1 | head -40 >&2 || true
        exit 1
    fi
    sleep 10
    waited=$((waited + 10))
done
echo "bootstrap prefix present after ${waited}s"
# Give the second stage (package postinst scripts) time to finish.
sleep 60
for tool in bash node npm git; do
    printf '%s: ' "$tool"
    adb shell run-as "$pkg" files/usr/bin/bash -c "'export PREFIX=/data/data/$pkg/files/usr HOME=/data/data/$pkg/files/home PATH=/data/data/$pkg/files/usr/bin; $tool --version 2>&1 | head -1'" || echo "(failed)"
done
