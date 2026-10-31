// SPDX-License-Identifier: GPL-3.0-only
package com.commandroid.integration

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.termux.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Several PTY sessions in one TermuxService: switching (the drawer's setCurrentSession path), the
 * NeoTerm draft's binding to the current session, and exiting one session while another lives on.
 */
@RunWith(AndroidJUnit4::class)
class SessionLifecycleTest {

    @Test fun switchingClearsTheDraftAndInputReachesOnlyTheCurrentSession() {
        PtyHarness.launch().use { h ->
            val baseline = h.sessionCount()
            val first = h.openSession("itest lifecycle 1")
            val second = h.openSession("itest lifecycle 2")
            assertEquals("Service does not list both new sessions", baseline + 2, h.sessionCount())
            assertTrue("Sessions must have distinct shells", h.onMain { first.pid != second.pid })

            h.switchTo(first)
            h.selectMode(R.id.neoterm_mode_text)
            val stale = h.commitDraft("draft_" + newNonce())
            h.switchTo(second)
            assertTrue("Session switch must discard the draft", h.draft().isEmpty())
            assertFalse("A stale IME connection must not write into the new session's draft",
                h.onMain { stale.commitText("late", 1) })
            assertTrue("Stale IME commit changed the draft", h.draft().isEmpty())

            // Transmit + Enter on the second session reaches only the second session.
            val two = newNonce()
            h.commitDraft(printMarker("TWO", two))
            h.click(R.id.neoterm_transmit)
            assertTrue("Transmit did not hand off the draft", h.draft().isEmpty())
            h.click(R.id.neoterm_enter)
            h.awaitMarker(second, "TWO", two)
            assertFor("Input for the current session leaked into another session") {
                !h.transcript(first).contains(marker("TWO", two))
            }

            // And after switching back, only the first.
            h.switchTo(first)
            val one = newNonce()
            h.commitDraft(printMarker("ONE", one))
            h.click(R.id.neoterm_transmit)
            h.click(R.id.neoterm_enter)
            h.awaitMarker(first, "ONE", one)
            assertFor("Input for the current session leaked into another session") {
                !h.transcript(second).contains(marker("ONE", one))
            }
            assertEquals("Second session's command ran more than once", 1,
                occurrences(h.transcript(second), marker("TWO", two)))
        }
    }

    @Test fun exitingOneSessionFinishesAndRemovesItWhileTheOtherKeepsWorking() {
        PtyHarness.launch().use { h ->
            val baseline = h.sessionCount()
            val leaving = h.openSession("itest exit")
            val staying = h.openSession("itest stays")
            assertEquals(baseline + 2, h.sessionCount())

            h.run(leaving, "exit")
            waitUntil("exit did not end the session's shell") { h.onMain { !leaving.isRunning } }
            assertEquals("exit should end the shell with status 0", 0, h.onMain { leaving.exitStatus })
            // A clean exit is removed automatically (TermuxTerminalSessionClient.onSessionFinished);
            // Android TV keeps it until removed, which is the explicit path below.
            if (h.isListed(leaving)) h.onMain { it.termuxTerminalSessionClient.removeFinishedSession(leaving) }
            waitUntil("Finished session is still listed by the service") { !h.isListed(leaving) }
            assertEquals("Removing one session changed the others", baseline + 1, h.sessionCount())

            assertTrue("Surviving session stopped", h.onMain { staying.isRunning })
            assertEquals("Surviving session no longer runs commands", "alive",
                h.capture(staying, "STAY", "alive"))
            h.switchTo(staying)
            h.selectMode(R.id.neoterm_mode_text)
            val nonce = newNonce()
            h.commitDraft(printMarker("AFTER", nonce))
            h.click(R.id.neoterm_transmit)
            h.click(R.id.neoterm_enter)
            h.awaitMarker(staying, "AFTER", nonce)
        }
    }
}
