// SPDX-License-Identifier: GPL-3.0-only
package com.commandroid.integration

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.commandroid.runtime.AppRuntime
import com.termux.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Terminal capabilities that interactive agent CLIs (Claude Code, Codex, OpenCode) need from the
 * PTY session before they can work at all: real ttys, a window size that tracks the view with
 * SIGWINCH, raw-mode byte-exact keys, bracketed paste, the alternate screen and TERM. The agents
 * themselves are not installed here; the bootstrap-prefix test exercises bash/node/git when present.
 */
@RunWith(AndroidJUnit4::class)
class InteractiveReadinessTest {

    @Test fun stdinAndStdoutAreTtysAndTermIsXterm256color() {
        PtyHarness.launch().use { h ->
            val shell = h.openSession("itest tty")
            val nonce = newNonce()
            h.run(shell, "if [ -t 0 ] && [ -t 1 ]; then itty=yes; else itty=no; fi; " +
                printValue("TTY", nonce, "\"\$itty:\$(tty)\""))
            val tty = h.awaitValue(shell, "TTY", nonce)
            assertTrue("stdin/stdout of a session command must both be ttys", tty.startsWith("yes:"))
            assertTrue("Session tty must be a pseudo-terminal", tty.removePrefix("yes:").startsWith("/dev/pts/"))
            assertEquals("xterm-256color", h.capture(shell, "TERM", "\"\$TERM\""))
            assertEquals("truecolor", h.capture(shell, "COLORTERM", "\"\$COLORTERM\""))
            assertTrue("Locale must be UTF-8", h.capture(shell, "LANG", "\"\$LANG\"").endsWith("UTF-8"))
        }
    }

    @Test fun windowSizeMatchesTheEmulatorAndResizeDeliversSigwinch() {
        PtyHarness.launch().use { h ->
            val shell = h.openSession("itest winsize")
            // The view resizes the session while the soft keyboard animates in; compare only a
            // settled size, and retry if the view resized it between the two readings.
            var rows = 0
            var cols = 0
            var size = ""
            for (attempt in 1..5) {
                val before = h.awaitStableSize(shell)
                size = h.capture(shell, "SIZE", "\"\$(stty size)\"")
                val after = h.emulatorSize(shell)
                rows = after.first; cols = after.second
                if (before == after) break
            }
            assertEquals("TIOCGWINSZ does not match the emulator", "$rows $cols", size)

            // A child traps WINCH and reports the size it then sees; `sleep 1` lets the trap run.
            val nonce = newNonce()
            h.run(shell, "sh -c 'trap \"printf \\\"\\\\n%s_%s:%s:END\\\\n\\\" WINCH $nonce \\\"\\\$(stty size)\\\"\" WINCH; " +
                "printf \"\\n%s_%s\\n\" WREADY $nonce; while :; do sleep 1; done'")
            h.awaitMarker(shell, "WREADY", nonce)
            h.awaitStableSize(shell)
            val newCols = maxOf(20, cols - 7)
            val newRows = maxOf(8, rows - 3)
            try {
                h.onMain { activity ->
                    val renderer = activity.terminalView.mRenderer
                    shell.updateSize(newCols, newRows, renderer.fontWidth.toInt(), renderer.fontLineSpacing)
                }
                assertEquals("Emulator did not take the new size", newRows to newCols,
                    h.onMain { shell.emulator.mRows to shell.emulator.mColumns })
                h.awaitMarker(shell, "WINCH", "$nonce:$newRows $newCols:END", 10_000,
                    "Resize did not deliver SIGWINCH with the new TIOCGWINSZ")
                h.write(shell, "\u0003")
                h.awaitShellPrompt(shell, 10_000, "Ctrl-C did not end the WINCH reporter")
                // Compared with the emulator as it is now, in case a layout pass resized it since.
                val resized = h.capture(shell, "RESIZED", "\"\$(stty size)\"")
                val now = h.onMain { "${shell.emulator.mRows} ${shell.emulator.mColumns}" }
                assertEquals("stty size did not follow the resize", now, resized)
            } finally {
                // Give the session back to the view's own geometry.
                h.onMain { it.terminalView.updateSize() }
            }
        }
    }

    @Test fun rawModeDeliversAnArrowKeyByteExact() {
        PtyHarness.launch().use { h ->
            val shell = h.openSession("itest raw")
            val nonce = newNonce()
            h.run(shell, "$STTY_RAW; ${printValue("RAWUP", nonce, RAW_STATE)}; " +
                "rk=\$(dd bs=1 count=3 2>/dev/null | $HEX_PIPE); stty sane; " +
                printValue("RAW", nonce, "\"\$sttyStatus:\$rk\""))
            val state = h.awaitValue(shell, "RAWUP", nonce)
            val raw = isRawTty(state)
            // Cursor-up as TerminalView sends it in normal cursor-key mode; a canonical tty also
            // needs the line ended before dd sees the bytes.
            h.write(shell, if (raw) "\u001b[A" else "\u001b[A\r")
            val read = h.awaitValue(shell, "RAW", nonce, 10_000)
            assertEquals("PTY did not pass ESC [ A through unchanged (raw=$raw; refused stty options:hex = $read; $state)",
                "1b5b41", read.substringAfter(':'))
        }
    }

    @Test fun bracketedPasteWrapsAMultiLineTransmitAndAReaderReceivesItIntact() {
        PtyHarness.launch().use { h ->
            val shell = h.openSession("itest paste")
            val nonce = newNonce()
            val body = "first line\nsecond line"
            val payload = "\u001b[200~$body\u001b[201~"
            val count = payload.toByteArray(Charsets.UTF_8).size
            // The reader enables bracketed paste like an agent's TUI does, then reads raw bytes.
            h.run(shell, "printf '\\033[?2004h'; $STTY_RAW; ${printValue("BPUP", nonce, RAW_STATE)}; " +
                "bp=\$(dd bs=1 count=$count 2>/dev/null | $HEX_PIPE); stty sane; printf '\\033[?2004l'; " +
                printValue("BP", nonce, "\"\$sttyStatus:\$bp\""))
            val raw = isRawTty(h.awaitValue(shell, "BPUP", nonce))
            waitUntil("Emulator did not enter bracketed paste mode") {
                h.onMain { shell.emulator.isBracketedPasteModeEnabled }
            }

            h.selectMode(R.id.neoterm_mode_text)
            h.commitDraft(body)
            h.click(R.id.neoterm_transmit)
            assertEquals(h.onMain { it.getString(R.string.neoterm_transmitted) }, h.status())
            // A canonical tty releases the paste's last line only once it is ended. The extra
            // newline is not part of the count dd reads, so the payload check stays byte-exact.
            if (!raw) h.write(shell, "\r")
            val received = h.awaitValue(shell, "BP", nonce, 10_000, "Reader did not receive the paste")
            assertEquals("Bracketed paste did not arrive intact (refused stty options:hex = $received)",
                utf8Hex(payload), received.substringAfter(':'))
            waitUntil("Reader did not leave bracketed paste mode") {
                h.onMain { !shell.emulator.isBracketedPasteModeEnabled }
            }
        }
    }

    @Test fun alternateScreenTogglesTheEmulatorBuffer() {
        PtyHarness.launch().use { h ->
            val shell = h.openSession("itest altscreen")
            assertFalse("Fresh session started on the alternate screen", h.onMain { shell.emulator.isAlternateBufferActive })
            val alt = newNonce()
            h.run(shell, "printf '\\033[?1049h'; ${printMarker("ALT", alt)}")
            waitUntil("1049h did not switch to the alternate screen") { h.onMain { shell.emulator.isAlternateBufferActive } }
            h.awaitMarker(shell, "ALT", alt)

            val main = newNonce()
            h.run(shell, "printf '\\033[?1049l'; ${printMarker("MAIN", main)}")
            waitUntil("1049l did not restore the main screen") { h.onMain { !shell.emulator.isAlternateBufferActive } }
            h.awaitMarker(shell, "MAIN", main)
            assertFalse("Alternate-screen output leaked into the main screen",
                h.transcript(shell).contains(marker("ALT", alt)))
        }
    }

    /** Only where a bundled bootstrap has been installed into the prefix; skipped otherwise. */
    @Test fun compatPrefixBashAndNodeSeeAnInteractiveTerminal() {
        assumeTrue("No bootstrap prefix installed", AppRuntime.hasInstalledPrefix())
        PtyHarness.launch().use { h ->
            val shell = h.openSession("itest prefix")
            val (rows, cols) = h.onMain { shell.emulator.mRows to shell.emulator.mColumns }
            val bash = newNonce()
            h.run(shell, "\"\$PREFIX/bin/bash\" -c 'if [ -t 0 ] && [ -t 1 ]; then t=yes; else t=no; fi; " +
                "printf \"\\n%s_%s:%s:END\\n\" BASH $bash \"\$t\"'")
            assertEquals("Prefix bash does not run on a tty", "yes", h.awaitValue(shell, "BASH", bash))

            // SIGINT to a bash child with an INT trap, as for the system shell.
            val nonce = newNonce()
            h.run(shell, "\"\$PREFIX/bin/bash\" -c 'trap \"printf \\\"\\\\n%s_%s\\\\n\\\" BTRAP $nonce; exit 7\" INT; " +
                "printf \"\\n%s_%s\\n\" BUP $nonce; while :; do :; done'")
            h.awaitMarker(shell, "BUP", nonce)
            h.write(shell, "\u0003")
            h.awaitMarker(shell, "BTRAP", nonce, 10_000, "SIGINT did not reach the bash child")
            assertEquals("7", h.capture(shell, "BSTATUS", "\"\$?\""))

            val hasNode = h.capture(shell, "NODE", "\"\$(command -v node >/dev/null 2>&1 && echo yes || echo no)\"")
            if (hasNode == "yes") {
                // What Ink-based TUIs check first: TTY streams, the window size and raw mode.
                val probe = newNonce()
                h.run(shell, "node -e 'const o=process.stdout,i=process.stdin;i.setRawMode(true);i.setRawMode(false);" +
                    "o.write(\"\\nNODE_\"+\"$probe:\"+i.isTTY+\" \"+o.isTTY+\" \"+o.rows+\" \"+o.columns+\":END\\n\")'")
                assertEquals("node does not see an interactive terminal", "true true $rows $cols",
                    h.awaitValue(shell, "NODE", probe))
            }
            val hasGit = h.capture(shell, "GIT", "\"\$(command -v git >/dev/null 2>&1 && echo yes || echo no)\"")
            if (hasGit == "yes")
                assertTrue("git does not run from the prefix",
                    h.capture(shell, "GITV", "\"\$(git --version 2>&1)\"").startsWith("git version"))
        }
    }

    /**
     * [state] is "<refused stty options or 0>:<stty -a>|err=<stty's error text>", taken right after
     * trying to enter raw mode. Returns whether the tty is non-canonical and non-echoing.
     *
     * Interactive CLIs set raw mode themselves through termios (node/libuv, Rust crossterm); the
     * platform's toybox stty is only this test's way to do it without a bootstrap. Android 11's
     * toybox stty refuses every option that would change the mode (CI, API 30: -icanon, -echo,
     * -isig and -icrnl refused, already-set options accepted), so on such images the byte-exact
     * delivery is checked through a canonical tty instead. From API 34, where toybox stty works
     * (CI, API 34), raw mode is required so a regression there still fails.
     */
    private fun isRawTty(state: String): Boolean {
        val settings = state.substringAfter(':').substringBefore("|err=").split(' ', ';').map { it.trim() }
        val raw = "-icanon" in settings && "-echo" in settings
        if (!raw) assertTrue("stty could not enter raw mode on API ${Build.VERSION.SDK_INT}; refused:settings = $state",
            Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        return raw
    }

    private companion object {
        /** stty's status and the resulting settings on one line (no CR/LF; -opost is in effect). */
        const val RAW_STATE = "\"\$sttyStatus:\$(stty -a 2>&1 | tr '\\r\\n' '  ')|err=\$sttyErr\""
    }
}
