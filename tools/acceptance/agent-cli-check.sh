#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
#
# commandroid-line agent CLI acceptance helper (docs/neoterm/AGENT-CLI-ACCEPTANCE.md).
#
# Run it INSIDE the app's terminal session, in the compat runtime:
#     sh agent-cli-check.sh [--resize] [--interrupt]
#
# It is read-only and non-destructive: it installs nothing, changes no terminal
# or system settings, writes no files itself, and runs each agent CLI only with
# --version and stdin from /dev/null (a CLI may still touch its own config).
# It never prints the environment, credentials or API keys. Of the environment
# it only reports TERM, COLORTERM, the locale variables, PREFIX, HOME and
# NEOTERM_RUNTIME.
#
# Results are PASS, FAIL or SKIP. INFO lines are not counted. The exit status is
# 1 if any check failed, 0 otherwise. A SKIP of a CLI means "not installed",
# which does not meet acceptance.
#
# Interactive checks (off by default):
#   --resize     waits up to 30 s for a SIGWINCH; rotate or resize the window.
#   --interrupt  runs `sleep 20`; press Ctrl-C. Checks that the foreground job
#                gets SIGINT while this script and its parent shell survive.

PASS=0
FAIL=0
SKIP=0
FAILED_NAMES=""
DO_RESIZE=0
DO_INTERRUPT=0
TIMEOUT_SECONDS=30

for arg in "$@"; do
    case "$arg" in
        --resize) DO_RESIZE=1 ;;
        --interrupt) DO_INTERRUPT=1 ;;
        -h|--help)
            sed -n '3,23p' "$0" | sed 's/^# \{0,1\}//'
            exit 0
            ;;
        *)
            printf 'unknown argument: %s (try --help)\n' "$arg" >&2
            exit 2
            ;;
    esac
done

pass() { PASS=$((PASS + 1)); printf 'PASS  %-22s %s\n' "$1" "$2"; }
fail() { FAIL=$((FAIL + 1)); FAILED_NAMES="$FAILED_NAMES $1"; printf 'FAIL  %-22s %s\n' "$1" "$2"; }
skip() { SKIP=$((SKIP + 1)); printf 'SKIP  %-22s %s\n' "$1" "$2"; }
info() { printf 'INFO  %-22s %s\n' "$1" "$2"; }

have() { command -v "$1" >/dev/null 2>&1; }

# Runs a command with stdin closed and sets OUT to the first line of its output
# (control characters removed, at most 100 characters) and STATUS to its exit
# status. Uses timeout(1) when available so a hanging CLI cannot block the run.
capture() {
    if have timeout; then
        raw=$(timeout "$TIMEOUT_SECONDS" "$@" </dev/null 2>&1)
    else
        raw=$("$@" </dev/null 2>&1)
    fi
    STATUS=$?
    OUT=$(printf '%s\n' "$raw" | sed -n '1{s/[[:cntrl:]]//g;p;}' | cut -c1-100)
}

# Leading integer of a version string such as "v20.11.1" -> 20.
major_of() { printf '%s\n' "$1" | sed -n 's/^[^0-9]*\([0-9][0-9]*\).*/\1/p'; }

printf 'commandroid-line agent CLI check (%s)\n\n' "$(date 2>/dev/null)"

# --- Runtime ----------------------------------------------------------------
info runtime "NEOTERM_RUNTIME=${NEOTERM_RUNTIME:-<unset>} PREFIX=${PREFIX:-<unset>}"
info home "HOME=${HOME:-<unset>}"
info arch "$(uname -m 2>/dev/null)"
case "${PREFIX:-}" in
    */com.termux/*|*/com.termux)
        fail prefix-identity "PREFIX belongs to official Termux; this must run in commandroid-line" ;;
    */com.commandroid_line/files/usr)
        if [ -x "$PREFIX/bin/bash" ]; then
            pass prefix-identity "compat prefix with bash"
        else
            fail prefix-identity "compat prefix has no bin/bash (no bootstrap installed)"
        fi ;;
    */com.commandroid_line.system/*)
        fail prefix-identity "system flavor: agent CLIs need the compat runtime" ;;
    "")
        fail prefix-identity "PREFIX is unset; not a commandroid-line session" ;;
    *)
        fail prefix-identity "unexpected PREFIX (expected .../com.commandroid_line/files/usr)" ;;
esac
if [ "${NEOTERM_RUNTIME:-}" = "android-system" ]; then
    fail runtime-shell "Android system shell runtime; install the compat bootstrap"
else
    pass runtime-shell "not the Android system-shell fallback"
fi

# --- Terminal ---------------------------------------------------------------
if [ -t 0 ]; then pass stdin-tty "$(tty 2>/dev/null)"; else fail stdin-tty "stdin is not a terminal"; fi
if [ -t 1 ]; then pass stdout-tty "stdout is a terminal"; else fail stdout-tty "stdout is not a terminal"; fi

if have stty && [ -t 0 ]; then
    size=$(stty size 2>/dev/null)
    rows=${size% *}
    cols=${size#* }
    if [ -n "$size" ] && [ "${rows:-0}" -gt 0 ] 2>/dev/null && [ "${cols:-0}" -gt 0 ] 2>/dev/null; then
        pass winsize "rows=$rows cols=$cols (TIOCGWINSZ)"
    else
        fail winsize "stty size returned '${size}'"
    fi
    settings=$(stty -a 2>/dev/null)
    case "$settings" in
        *-isig*) fail isig "ISIG is off; Ctrl-C would not raise SIGINT" ;;
        *isig*) pass isig "line discipline generates signals" ;;
        *) skip isig "stty -a did not report isig" ;;
    esac
    case "$settings" in
        *"intr = ^C"*) pass intr-char "intr = ^C" ;;
        *) fail intr-char "interrupt character is not ^C" ;;
    esac
    case "$settings" in
        *-iutf8*) fail iutf8 "IUTF8 is off" ;;
        *iutf8*) pass iutf8 "IUTF8 is on" ;;
        *) skip iutf8 "stty -a did not report iutf8" ;;
    esac
else
    skip stty "stty unavailable or stdin is not a terminal"
fi

case "${TERM:-}" in
    *256color*) pass term "TERM=$TERM" ;;
    "") fail term "TERM is unset" ;;
    *) fail term "TERM=$TERM (expected xterm-256color)" ;;
esac
case "${COLORTERM:-}" in
    truecolor|24bit) pass colorterm "COLORTERM=$COLORTERM" ;;
    *) fail colorterm "COLORTERM=${COLORTERM:-<unset>} (expected truecolor)" ;;
esac
locale_value=${LC_ALL:-${LC_CTYPE:-${LANG:-}}}
case "$locale_value" in
    *[Uu][Tt][Ff]-8*|*[Uu][Tt][Ff]8*) pass locale "$locale_value" ;;
    *) fail locale "LC_ALL/LC_CTYPE/LANG='${locale_value}' is not UTF-8" ;;
esac
info unicode "visual check: é ñ ü 中文 日本語 한국어 👩‍💻 e$(printf '\314\201')"

# --- Toolchain from the compat bootstrap -----------------------------------
if have node; then
    capture node --version
    node_version=$OUT
    node_major=$(major_of "$node_version")
    if [ -n "$node_major" ] && [ "$node_major" -ge 18 ]; then
        pass node "$node_version"
    else
        fail node "$node_version (expected 18 or newer; to be confirmed per CLI)"
    fi
else
    fail node "node not found on PATH"
fi
for tool in npm git; do
    if have "$tool"; then
        capture "$tool" --version
        if [ "$STATUS" -eq 0 ]; then pass "$tool" "$OUT"; else fail "$tool" "--version failed: $OUT"; fi
    else
        fail "$tool" "$tool not found on PATH"
    fi
done

# --- Agent CLIs (version only) ---------------------------------------------
for cli in claude codex opencode; do
    if have "$cli"; then
        capture "$cli" --version
        if [ "$STATUS" -eq 0 ] && [ -n "$OUT" ]; then
            pass "$cli" "$OUT"
        else
            fail "$cli" "--version failed (status $STATUS): ${OUT:-<no output>}"
        fi
    else
        skip "$cli" "not installed (acceptance not met)"
    fi
done

# --- Optional interactive checks -------------------------------------------
if [ "$DO_RESIZE" -eq 1 ]; then
    if [ -t 0 ] && have stty; then
        before=$(stty size 2>/dev/null)
        got_winch=0
        trap 'got_winch=1' WINCH
        printf '\nRotate the phone or resize the window now (waiting up to 30 s)...\n'
        waited=0
        while [ "$got_winch" -eq 0 ] && [ "$waited" -lt 30 ]; do
            sleep 1
            waited=$((waited + 1))
        done
        trap - WINCH
        after=$(stty size 2>/dev/null)
        if [ "$got_winch" -eq 1 ]; then
            pass sigwinch "received; size $before -> $after"
        else
            fail sigwinch "no SIGWINCH within 30 s (size $before -> $after)"
        fi
    else
        skip sigwinch "needs a terminal and stty"
    fi
fi

if [ "$DO_INTERRUPT" -eq 1 ]; then
    if [ -t 0 ]; then
        got_int=0
        parent=$PPID
        trap 'got_int=1' INT
        printf '\nPress Ctrl-C now (sleep 20 is running in the foreground)...\n'
        sleep 20
        sleep_status=$?
        trap - INT
        if [ "$got_int" -eq 1 ] && [ "$sleep_status" -gt 128 ]; then
            pass sigint-foreground "sleep ended by a signal (status $sleep_status); script survived"
        elif [ "$got_int" -eq 1 ]; then
            pass sigint-foreground "SIGINT delivered; script survived"
        else
            fail sigint-foreground "no SIGINT seen within 20 s"
        fi
        if kill -0 "$parent" 2>/dev/null; then
            pass parent-shell "parent shell (pid $parent) still alive"
        else
            fail parent-shell "parent shell (pid $parent) is gone"
        fi
    else
        skip sigint-foreground "needs a terminal"
    fi
fi

# --- Summary ----------------------------------------------------------------
printf '\nSummary: %d PASS, %d FAIL, %d SKIP\n' "$PASS" "$FAIL" "$SKIP"
if [ "$FAIL" -gt 0 ]; then
    printf 'Failed:%s\n' "$FAILED_NAMES"
    exit 1
fi
exit 0
