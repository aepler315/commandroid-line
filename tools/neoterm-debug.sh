#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
set -euo pipefail
cd "$(dirname "$0")/.."
: "${JAVA_HOME:?Set JAVA_HOME to a JDK 11 installation}"
if [[ ! -x "$JAVA_HOME/bin/javac" ]] || [[ "$("$JAVA_HOME/bin/javac" -version 2>&1)" != *javac\ 11.* ]]; then
    echo 'NeoTerm debug builds require JDK 11 (including javac).' >&2
    exit 1
fi
export TERMUX_APP_VERSION_NAME=0.118.3-neoterm.standalone2
export TERMUX_APK_VERSION_TAG=standalone-debug
export TERMUX_SPLIT_APKS_FOR_DEBUG_BUILDS=0
./gradlew --no-daemon --max-workers=4 :neoterm-input:test testDebugUnitTest \
    :app:assembleDebug :app:assembleDebugAndroidTest
