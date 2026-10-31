// SPDX-License-Identifier: GPL-3.0-only
package com.commandroid.integration

import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.termux.terminal.TerminalSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A session's shell dying abnormally (SIGKILL, as after a crash or the OOM killer) ends only that
 * session; the user can remove it and start a new, fully working one, repeatedly, without finished
 * sessions accumulating in TermuxService.
 */
@RunWith(AndroidJUnit4::class)
class ProcessRestartTest {

    @Test fun killedShellEndsOnlyItsSessionAndNewSessionsWorkWithoutLeaks() {
        PtyHarness.launch().use { h ->
            val baseline = h.sessionCount()
            val bystander = h.openSession("itest bystander")
            val bystanderPid = h.onMain { bystander.pid }
            val killed = mutableListOf<TerminalSession>()
            val isLeanback = h.onMain { it.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) }

            repeat(3) { round ->
                val victim = h.openSession("itest restart $round")
                assertEquals("Unexpected session count before kill", baseline + 2, h.sessionCount())
                val victimPid = h.onMain { victim.pid }
                assertTrue("New session reused a shell pid", victimPid != bystanderPid)
                assertEquals("New session is not fully functional", "ok$round", h.capture(victim, "WORKS", "ok$round"))

                h.run(victim, "kill -9 \$\$")
                waitUntil("kill -9 \$\$ did not end the session") { h.onMain { !victim.isRunning } }
                // TerminalSession reports a fatal signal as its negated number.
                assertEquals("Exit status must report SIGKILL", -9, h.onMain { victim.exitStatus })
                waitUntil("Session did not show its completion line") {
                    h.transcript(victim).contains("[Process completed (signal 9)")
                }
                // On phones a non-zero exit stays listed (so the user sees why) until removed, as
                // Enter does; Android TV removes it automatically.
                if (!isLeanback) assertTrue("Killed session vanished before removal", h.isListed(victim))
                if (h.isListed(victim)) h.onMain { it.termuxTerminalSessionClient.removeFinishedSession(victim) }
                waitUntil("Removed session is still listed") { !h.isListed(victim) }
                killed += victim

                assertEquals("Finished sessions leaked into the service list", baseline + 1, h.sessionCount())
                assertTrue("Killing one shell affected another session", h.onMain { bystander.isRunning })
                assertEquals("Bystander shell was replaced", bystanderPid, h.onMain { bystander.pid })
                assertEquals("Bystander no longer runs commands", "still$round",
                    h.capture(bystander, "BYSTANDER", "still$round"))
            }

            val fresh = h.openSession("itest after restarts")
            assertEquals("Session after restarts is not fully functional", "fresh",
                h.capture(fresh, "FRESH", "fresh"))
            h.onMain { activity ->
                val listed = activity.termuxService!!.termuxSessions.map { it.terminalSession }
                for (session in killed) assertFalse("Killed session is still listed", listed.contains(session))
            }
            assertEquals(baseline + 2, h.sessionCount())
        }
    }
}
