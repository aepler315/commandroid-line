// SPDX-License-Identifier: GPL-3.0-only
package com.neoterm.input

import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.RadioGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.termux.R
import com.termux.app.TermuxActivity
import com.termux.terminal.TerminalSession
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Real activity, controller, JNI PTY, and /system/bin/sh; no agent or vendor IME is simulated. */
@RunWith(AndroidJUnit4::class)
class NativePtyCompositionInstrumentationTest {
    @Test fun correctedParagraphWaitsForTransmitAndSeparateTerminalEnter() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals("com.neoterm", context.packageName)
        val nonce = UUID.randomUUID().toString().replace("-", "")
        // Distinct from shell protocol markers, so it cannot already occur in command echo.
        val draftToken = "draft_" + UUID.randomUUID().toString().replace("-", "")
        val paragraph = "$draftToken Please explain the failure. Preserve café, 中文 and 🙂 exactly."
        val ready = "NEOTERM_READY_$nonce"
        val received = "NEOTERM_RECEIVED_$nonce"
        val expectedOutput = "$received:$paragraph:END"
        val scenario = ActivityScenario.launch<TermuxActivity>(
            Intent(context, TermuxActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        var session: TerminalSession? = null
        try {
            waitUntil("Activity did not start a native shell") {
                activityValue(scenario) {
                    it.currentSession?.let { shell -> shell.pid > 0 && shell.emulator != null } == true
                }
            }
            activityValue(scenario) { activity ->
                val original = activity.currentSession
                activity.termuxTerminalSessionClient.addNewSession(false, "NeoTerm PTY regression")
                session = activity.currentSession
                assertNotNull("Dedicated native session was not created", session)
                assertNotSame("Regression must use its own terminal session", original, session)
            }
            val shell = session!!
            waitUntil("Dedicated native PTY did not initialize") {
                activityValue(scenario) { shell.pid > 0 && shell.emulator != null }
            }
            activityValue(scenario) {
                // Canonical echo makes premature bytes observable. read cannot complete before CR.
                // printf constructs markers absent from echoed command text, avoiding false positives.
                shell.write("stty echo icanon; printf '\\033[?2004l'; " +
                    "printf '\\nNEOTERM_READY_%s\\n' '$nonce'; " +
                    "IFS= read -r neoterm_fixture; " +
                    "printf '\\nNEOTERM_RECEIVED_%s:%s:END\\n' '$nonce' \"\$neoterm_fixture\"\r")
            }
            waitUntil("Native receiver did not become ready") { transcript(scenario, shell).contains(ready) }

            lateinit var oldConnection: InputConnection
            activityValue(scenario) { activity ->
                activity.findViewById<RadioGroup>(R.id.neoterm_modes).check(R.id.neoterm_mode_text)
                val editor = activity.findViewById<CompositionEditText>(R.id.neoterm_draft)
                assertTrue("TEXT mode must focus the native editor", editor.hasFocus())
                oldConnection = editor.onCreateInputConnection(EditorInfo())!!
                assertTrue(oldConnection.setComposingText("$draftToken Please explain teh failure.", 1))
                assertTrue(oldConnection.finishComposingText())
                val typo = editor.text.indexOf("teh")
                assertTrue(oldConnection.setComposingRegion(typo, typo + 3))
                assertTrue(oldConnection.commitText("the", 1))
                assertTrue(oldConnection.setSelection(editor.text.length, editor.text.length))
                assertTrue(oldConnection.commitText(" Preserve café, 中文 and 🙂 exactly.", 1))
                assertTrue(oldConnection.performEditorAction(EditorInfo.IME_ACTION_SEND))
                assertTrue("Correction must remain exact in the native draft", editor.text.toString() == paragraph)
            }
            assertFor("Draft or IME action reached the PTY before Transmit") {
                val output = transcript(scenario, shell)
                !output.contains(draftToken) && !output.contains(received)
            }

            activityValue(scenario) { activity ->
                assertTrue(activity.findViewById<View>(R.id.neoterm_transmit).performClick())
                val editor = activity.findViewById<CompositionEditText>(R.id.neoterm_draft)
                assertTrue("Successful handoff must clear the draft", editor.text.isEmpty())
                assertFalse("A stale IME must not replay transmitted text", oldConnection.commitText(paragraph, 1))
                // A second user tap must not replay the now-empty draft either.
                assertTrue(activity.findViewById<View>(R.id.neoterm_transmit).performClick())
            }
            waitUntil("Transmitted paragraph was not echoed by the native PTY") {
                transcript(scenario, shell).contains(paragraph)
            }
            assertFor("Transmit appended Enter or otherwise completed the native read") {
                !transcript(scenario, shell).contains(received)
            }

            activityValue(scenario) {
                assertTrue(it.findViewById<View>(R.id.neoterm_enter).performClick())
            }
            waitUntil("Explicit terminal Enter did not release read and execute printf") {
                transcript(scenario, shell).contains(expectedOutput)
            }
            val output = transcript(scenario, shell)
            assertTrue("Native receiver did not receive exactly one corrected paragraph", output.contains(expectedOutput))
            assertEquals("Native receiver printed more than once", 1, occurrences(output, received))

            // Only a non-sensitive random fixture is used. Never print captured log contents.
            val descriptor = instrumentation.uiAutomation.executeShellCommand(
                "logcat -d -v brief --pid=${Process.myPid()}"
            )
            val logs = ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
            assertFalse("App diagnostics disclosed draft input", logs.contains(draftToken))
        } finally {
            session?.let { shell ->
                activityValue(scenario) { shell.finishIfRunning() }
                waitUntil("Dedicated native shell did not stop") {
                    activityValue(scenario) { !shell.isRunning }
                }
                activityValue(scenario) { it.termuxTerminalSessionClient.removeFinishedSession(shell) }
            }
            scenario.close()
        }
    }

    private fun transcript(scenario: ActivityScenario<TermuxActivity>, shell: TerminalSession): String =
        activityValue(scenario) { shell.emulator?.screen?.transcriptText ?: "" }

    private fun <T> activityValue(scenario: ActivityScenario<TermuxActivity>, read: (TermuxActivity) -> T): T {
        val value = AtomicReference<T>()
        scenario.onActivity { value.set(read(it)) }
        return value.get()
    }

    private fun waitUntil(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 30_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(25) // Instrumentation thread only; activity/PTY callbacks keep running.
        }
        fail(message)
    }

    private fun assertFor(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 500
        do {
            assertTrue(message, condition())
            SystemClock.sleep(25)
        } while (SystemClock.uptimeMillis() < deadline)
    }

    private fun occurrences(text: String, marker: String): Int = text.windowed(marker.length).count { it == marker }
}
