#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
# Local equivalent of the "build" job in .github/workflows/debug_build.yml: lint both runtime
# flavors, run every JVM suite once, and build both flavors' debug app and androidTest APKs.
# Extra arguments are passed to Gradle (e.g. -PcommandroidBootstrapDir=<dir>, see
# docs/neoterm/BOOTSTRAP-BUILD.md). COMMANDROID_GRADLE_WORKERS overrides --max-workers.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${JAVA_HOME:?Set JAVA_HOME to a JDK 17 installation}"
if [[ ! -x "$JAVA_HOME/bin/javac" ]] || [[ "$("$JAVA_HOME/bin/javac" -version 2>&1)" != *javac\ 17.* ]]; then
    echo 'Commandroid debug builds require JDK 17 (including javac).' >&2
    exit 1
fi
export TERMUX_APP_VERSION_NAME=0.118.3-commandroid.1
export TERMUX_APK_VERSION_TAG=debug
export TERMUX_SPLIT_APKS_FOR_DEBUG_BUILDS=0
./gradlew --stacktrace --no-daemon --max-workers="${COMMANDROID_GRADLE_WORKERS:-2}" \
    :app:lintCompatDebug :app:lintSystemDebug \
    :neoterm-input:test :cmd-translator:test :terminal-emulator:testDebugUnitTest \
    :app:testCompatDebugUnitTest :app:testSystemDebugUnitTest \
    :app:assembleCompatDebug :app:assembleSystemDebug \
    :app:assembleCompatDebugAndroidTest :app:assembleSystemDebugAndroidTest \
    "$@"
