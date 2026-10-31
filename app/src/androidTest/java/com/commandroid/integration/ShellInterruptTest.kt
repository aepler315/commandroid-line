// SPDX-License-Identifier: GPL-3.0-only
package com.commandroid.integration

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Ctrl-C and Ctrl-D through the real PTY line discipline. The bytes are the ones TerminalView
 * sends for those keys (0x03, 0x04). Agent CLIs depend on exactly this: ^C must reach the
 * foreground process group, not end the session's shell.
 */
@RunWith(AndroidJUnit4::class)
class ShellInterruptTest {

    @Test fun ctrlCInterruptsForegroundSleepAndShellLoopWithoutEndingTheShell() {
        PtyHarness.launch().use { h ->
            val shell = h.openSession("itest interrupt")
            val pid = h.onMain { shell.pid }
            val shellPid = h.capture(shell, "PID", "\"\$\$\"")

            // External foreground job: the shell would otherwise wait 30 s.
            val sleeping = newNonce()
            h.run(shell, "${printMarker("SLEEPING", sleeping)}; sleep 30")
            h.awaitMarker(shell, "SLEEPING", sleeping)
            h.write(shell, "\u0003")
            val afterSleep = h.awaitShellPrompt(shell, 10_000, "Ctrl-C did not interrupt a foreground sleep 30")
            assertEquals("Ctrl-C replaced the shell", shellPid, afterSleep)

            // Busy loop of shell builtins: here the shell itself is the foreground process group.
            val busy = newNonce()
            h.run(shell, "${printMarker("BUSY", busy)}; while :; do :; done")
            h.awaitMarker(shell, "BUSY", busy)
            h.write(shell, "\u0003")
            val afterLoop = h.awaitShellPrompt(shell, 10_000, "Ctrl-C did not interrupt a busy loop")
            assertEquals("Ctrl-C replaced the shell", shellPid, afterLoop)

            assertTrue("Session stopped after Ctrl-C", h.onMain { shell.isRunning })
            assertEquals("Session pid changed after Ctrl-C", pid, h.onMain { shell.pid })
            assertEquals("A command after the interrupts did not run", "ok", h.capture(shell, "NEXT", "ok"))
        }
    }

    @Test fun sigintReachesTheForegroundChildProcessGroup() {
        PtyHarness.launch().use { h ->
            val shell = h.openSession("itest sigint trap")
            val shellPid = h.capture(shell, "PID", "\"\$\$\"")
            val nonce = newNonce()
            // The child shell traps INT, reports it and exits 7: only a delivered SIGINT prints TRAPPED.
            // Inside the single-quoted script, \" keeps the trap's printf arguments one word each.
            h.run(shell, "sh -c 'trap \"printf \\\"\\\\n%s_%s\\\\n\\\" TRAPPED $nonce; exit 7\" INT; " +
                "printf \"\\n%s_%s:%s:END\\n\" CHILD $nonce \"\$\$\"; while :; do :; done'")
            val childPid = h.awaitValue(shell, "CHILD", nonce, message = "Child shell did not start")
            assertTrue("Child must be a separate process", childPid != shellPid)
            h.write(shell, "\u0003")
            h.awaitMarker(shell, "TRAPPED", nonce, 10_000, "SIGINT did not reach the foreground child")
            // TRAPPED proves ^C was processed, so this line cannot be flushed by it.
            val status = h.capture(shell, "STATUS", "\"\$?:\$\$\"")
            assertEquals("Child did not exit through its INT trap, or the shell changed", "7:$shellPid", status)
            assertTrue("Session stopped after SIGINT", h.onMain { shell.isRunning })
        }
    }

    @Test fun ctrlDEndsCatWithEofWithoutEndingTheShell() {
        PtyHarness.launch().use { h ->
            val shell = h.openSession("itest eof")
            val pid = h.onMain { shell.pid }
            val shellPid = h.capture(shell, "PID", "\"\$\$\"")
            val nonce = newNonce()
            h.run(shell, "${printMarker("CATUP", nonce)}; cat; ${printValue("CATEOF", nonce, "\"\$?\"")}")
            h.awaitMarker(shell, "CATUP", nonce)
            assertFor("cat finished before EOF") { h.valueOrNull(shell, "CATEOF", nonce) == null }
            h.write(shell, "\u0004")
            val status = h.awaitValue(shell, "CATEOF", nonce, 10_000, "Ctrl-D did not end cat")
            assertEquals("cat did not exit normally on EOF", "0", status)
            assertTrue("Ctrl-D for cat ended the session", h.onMain { shell.isRunning })
            assertEquals("Session pid changed after Ctrl-D", pid, h.onMain { shell.pid })
            assertEquals("Ctrl-D replaced the shell", shellPid, h.capture(shell, "PID", "\"\$\$\""))
        }
    }
}
