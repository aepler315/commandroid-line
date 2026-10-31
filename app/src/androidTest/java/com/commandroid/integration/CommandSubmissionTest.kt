// SPDX-License-Identifier: GPL-3.0-only
package com.commandroid.integration

import android.os.ParcelFileDescriptor
import android.os.Process
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.termux.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Staged submission into a real shell line editor: Transmit writes the draft without Enter, the
 * separate Enter button submits exactly once, and no IME/editor action ever submits. CMD mode
 * writes the POSIX translation for review before Enter and refuses unsupported commands.
 */
@RunWith(AndroidJUnit4::class)
class CommandSubmissionTest {

    @Test fun transmitWritesWithoutExecutingAndEnterSubmitsExactlyOnce() {
        PtyHarness.launch().use { h ->
            val shell = h.openSession("itest submit")
            h.selectMode(R.id.neoterm_mode_text)
            val nonce = newNonce()
            val command = printMarker("SUBMIT", nonce)
            h.commitDraft(command)
            assertFor("Draft reached the PTY before Transmit") { !h.transcript(shell).contains(nonce) }

            h.click(R.id.neoterm_transmit)
            assertTrue("Transmit must clear the handed-off draft", h.draft().isEmpty())
            assertEquals(h.onMain { it.getString(R.string.neoterm_transmitted) }, h.status())
            waitUntil("Transmitted command was not echoed by the shell's line editor") {
                h.transcript(shell).contains(nonce)
            }
            assertFor("Transmit appended Enter or otherwise executed the command") {
                !h.transcript(shell).contains(marker("SUBMIT", nonce))
            }

            h.click(R.id.neoterm_enter)
            h.awaitMarker(shell, "SUBMIT", nonce, message = "Enter did not execute the transmitted command")
            // A second Enter submits an empty line; it must not replay the command.
            h.click(R.id.neoterm_enter)
            val after = newNonce()
            h.run(shell, printMarker("AFTER", after))
            h.awaitMarker(shell, "AFTER", after)
            assertEquals("Command executed more than once", 1, occurrences(h.transcript(shell), marker("SUBMIT", nonce)))
        }
    }

    @Test fun enterWithAPendingDraftDoesNotSubmit() {
        PtyHarness.launch().use { h ->
            val shell = h.openSession("itest pending")
            h.selectMode(R.id.neoterm_mode_text)
            val nonce = newNonce()
            val command = printMarker("PENDING", nonce)
            h.commitDraft(command)
            h.click(R.id.neoterm_enter)
            assertEquals(h.onMain { it.getString(R.string.neoterm_unsent_draft) }, h.status())
            assertTrue("Enter with a pending draft must keep it", h.draft() == command)
            assertFor("Enter with a pending draft wrote to the PTY") { !h.transcript(shell).contains(nonce) }
            h.click(R.id.neoterm_clear)
            assertTrue("Clear did not discard the draft", h.draft().isEmpty())
            assertFor("Clear wrote to the PTY") { !h.transcript(shell).contains(nonce) }
        }
    }

    @Test fun imeCommitEditorActionsAndHardwareEnterNeverSubmit() {
        PtyHarness.launch().use { h ->
            val shell = h.openSession("itest ime")
            h.selectMode(R.id.neoterm_mode_text)
            assertTrue("TEXT mode must focus the draft editor", h.onMain { h.editor(it).hasFocus() })
            val nonce = newNonce()
            val command = printMarker("IME", nonce)
            val connection = h.commitDraft(command)
            h.onMain {
                for (action in intArrayOf(EditorInfo.IME_ACTION_SEND, EditorInfo.IME_ACTION_GO,
                        EditorInfo.IME_ACTION_DONE, EditorInfo.IME_ACTION_UNSPECIFIED)) {
                    connection.performEditorAction(action)
                }
            }
            assertTrue("Editor actions changed the draft", h.draft() == command)
            assertFor("An IME editor action reached the PTY") { !h.transcript(shell).contains(nonce) }

            // Hardware Enter (as from a Bluetooth/USB keyboard) adds a line break to the draft.
            h.instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER)
            h.instrumentation.waitForIdleSync()
            if (h.draft() != command + "\n") {
                // Diagnose before failing: the draft is a non-secret fixture. commitDraft() opened
                // its own InputConnection, which invalidates the IME's; if the platform IME turns a
                // hardware Enter into an edit on its (now stale) connection, the edit is dropped.
                val before = enterState(h, shell, nonce)
                h.onMain { activity ->
                    val editor = h.editor(activity)
                    (activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                        as android.view.inputmethod.InputMethodManager).restartInput(editor)
                }
                h.instrumentation.waitForIdleSync()
                h.instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER)
                h.instrumentation.waitForIdleSync()
                fail("Hardware Enter must edit the draft, not submit it. After Enter: $before | " +
                    "after restartInput + Enter: ${enterState(h, shell, nonce)}")
            }
            assertFor("Hardware Enter in the draft reached the PTY") { !h.transcript(shell).contains(nonce) }

            // Without bracketed paste a multi-line draft is refused, not split into commands.
            // (/system/bin/sh never enables it; bash's readline does at its prompt, and then
            // Transmit would paste into readline instead, which the JVM tests cover.)
            if (h.onMain { shell.emulator?.isBracketedPasteModeEnabled } == false) {
                h.click(R.id.neoterm_transmit)
                assertEquals(h.onMain { it.getString(R.string.neoterm_bracketed_required) }, h.status())
                assertTrue("Refused Transmit must keep the draft", h.draft() == command + "\n")
                assertFor("Refused multi-line Transmit wrote to the PTY") { !h.transcript(shell).contains(nonce) }
            }

            // Only a non-sensitive random fixture is used. Never print captured log contents.
            val descriptor = h.instrumentation.uiAutomation.executeShellCommand(
                "logcat -d -v brief --pid=${Process.myPid()}"
            )
            val logs = ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
            assertFalse("App diagnostics disclosed draft input", logs.contains(nonce))
        }
    }

    @Test fun cmdModeWritesThePosixTranslationForReviewAndRunsItOnlyOnEnter() {
        PtyHarness.launch().use { h ->
            val shell = h.openSession("itest cmd")
            val nonce = newNonce().uppercase()
            val variable = "CMDT_$nonce"
            // The shell variable's value is built by printf, so it never appears in a command echo.
            h.run(shell, "$variable=\$(printf '%s_%s' CMDOUT $nonce); ${printMarker("CMDSET", nonce)}")
            h.awaitMarker(shell, "CMDSET", nonce)

            h.onMain { it.findViewById<CheckBox>(R.id.neoterm_cmd).isChecked = true }
            assertTrue("CMD mode must open the draft editor", h.onMain { h.editor(it).engine.isComposing })
            h.commitDraft("echo %$variable%")
            h.click(R.id.neoterm_transmit)
            assertTrue("Translated Transmit must clear the draft", h.draft().isEmpty())
            // Only the translator produces ${NAME}; the CMD draft spelled %NAME%.
            val posix = "echo \${$variable}"
            waitUntil("POSIX translation did not appear on the shell line") { h.transcript(shell).contains(posix) }
            assertFor("Translated command ran before Enter, or the CMD text was written") {
                val text = h.transcript(shell)
                !text.contains(marker("CMDOUT", nonce)) && !text.contains("%$variable%")
            }
            h.click(R.id.neoterm_enter)
            h.awaitMarker(shell, "CMDOUT", nonce, message = "Translated command did not run on Enter")

            // Unsupported Win32 tool: refused, nothing written, draft kept, reason shown.
            val refused = "regedit /s rk$nonce.reg"
            h.commitDraft(refused)
            h.click(R.id.neoterm_transmit)
            assertTrue("Refused draft must be kept", h.draft() == refused)
            val reason = h.onMain { h.editor(it).engine.lastRefusal }
            assertTrue("Refusal must name the unsupported command", reason?.startsWith("regedit") == true)
            assertEquals(h.onMain { it.getString(R.string.commandroid_cmd_refused, reason) }, h.status())
            assertFor("Refused CMD draft was written to the PTY") { !h.transcript(shell).contains("rk$nonce") }
        }
    }

    /** One-line state for an Enter failure: draft (escaped), selection, focus, IME, PTY reach. */
    private fun enterState(h: PtyHarness, shell: com.termux.terminal.TerminalSession, nonce: String): String =
        h.onMain { activity ->
            val editor = h.editor(activity)
            val draft = editor.text.toString().replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r")
            val ime = android.provider.Settings.Secure.getString(activity.contentResolver,
                android.provider.Settings.Secure.DEFAULT_INPUT_METHOD)
            "draft=\"$draft\" sel=${editor.selectionStart}..${editor.selectionEnd} " +
                "editorFocused=${editor.hasFocus()} focus=${activity.currentFocus?.javaClass?.simpleName} " +
                "composing=${editor.engine.isComposing} ime=$ime api=${android.os.Build.VERSION.SDK_INT} " +
                "reachedPty=${shell.emulator?.screen?.transcriptText?.contains(nonce)}"
        }
}
