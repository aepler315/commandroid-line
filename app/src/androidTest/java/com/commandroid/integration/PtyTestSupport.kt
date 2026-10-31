// SPDX-License-Identifier: GPL-3.0-only
package com.commandroid.integration

import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.RadioGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.neoterm.input.CompositionEditText
import com.termux.R
import com.termux.app.TermuxActivity
import com.termux.app.TermuxService
import com.termux.terminal.TerminalSession
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/*
 * Shared harness for the PTY integration tests. Everything here drives the real TermuxActivity,
 * TermuxService, JNI PTY and the session's shell (/system/bin/sh, or the compat prefix's login
 * shell once a bootstrap is installed). Shell snippets use only POSIX sh syntax and tools that
 * Android's /system/bin provides (mksh, toybox), so they run on both flavors.
 *
 * Markers: every observable result is printed as "<TAG>_<nonce>" by printf with TAG and nonce as
 * separate arguments, so the echoed command line can never contain the marker itself.
 *
 * Privacy: assertion messages never include drafts or transcripts (no assertEquals on them).
 */

/** Generous per-step timeout: emulators on CI are slow. */
internal const val TIMEOUT_MS = 30_000L

/** Short random hex, so marker lines stay narrower than a phone-width terminal. */
internal fun newNonce(): String = UUID.randomUUID().toString().replace("-", "").take(12)

internal fun marker(tag: String, nonce: String) = "${tag}_$nonce"

/** Shell snippet printing "\n<TAG>_<nonce>\n". */
internal fun printMarker(tag: String, nonce: String) = "printf '\\n%s_%s\\n' '$tag' '$nonce'"

/** Shell snippet printing "\n<TAG>_<nonce>:<value>:END\n"; [valueExpr] is shell syntax, already quoted. */
internal fun printValue(tag: String, nonce: String, valueExpr: String) =
    "printf '\\n%s_%s:%s:END\\n' '$tag' '$nonce' $valueExpr"

internal fun occurrences(text: String, needle: String): Int {
    var count = 0
    var index = text.indexOf(needle)
    while (index >= 0) {
        count++
        index = text.indexOf(needle, index + needle.length)
    }
    return count
}

/** Lower-case hex of the UTF-8 bytes, no separators, as `od -An -v -tx1 | tr -d ' \n'` prints it. */
internal fun utf8Hex(text: String): String =
    text.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it.toInt() and 0xff) }

/** Shell pipeline printing stdin as unseparated lower-case hex. */
internal const val TRANSCRIPT_TAIL_LINES = 15

/**
 * Non-canonical, no-echo, no-signal input with explicit VMIN/VTIME: what a TUI's raw mode needs
 * for key bytes. Spelled out instead of toybox's `raw` combo so a missing combo flag on an older
 * toybox cannot leave the tty canonical. `$sttyStatus` keeps stty's exit status for the report.
 */
internal const val STTY_RAW = "stty -icanon -echo -isig -icrnl -ixon min 1 time 0; sttyStatus=\$?"

internal const val HEX_PIPE = "od -An -v -tx1 | tr -d ' \\n'"

internal fun waitUntil(message: String, timeoutMs: Long = TIMEOUT_MS, condition: () -> Boolean) {
    val deadline = SystemClock.uptimeMillis() + timeoutMs
    while (SystemClock.uptimeMillis() < deadline) {
        if (condition()) return
        SystemClock.sleep(25) // Instrumentation thread only; activity and PTY callbacks keep running.
    }
    fail(message)
}

/** Asserts that [condition] holds continuously for [durationMs]. */
internal fun assertFor(message: String, durationMs: Long = 750, condition: () -> Boolean) {
    val deadline = SystemClock.uptimeMillis() + durationMs
    do {
        assertTrue(message, condition())
        SystemClock.sleep(25)
    } while (SystemClock.uptimeMillis() < deadline)
}

/**
 * One launched TermuxActivity plus the dedicated sessions a test opened. [close] kills and removes
 * every owned session and then closes the activity, so tests never share shell state.
 */
internal class PtyHarness private constructor(val scenario: ActivityScenario<TermuxActivity>) : AutoCloseable {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val owned = mutableListOf<TerminalSession>()

    companion object {
        fun launch(): PtyHarness {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val scenario = ActivityScenario.launch<TermuxActivity>(
                Intent(context, TermuxActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            val harness = PtyHarness(scenario)
            try {
                waitUntil("Activity did not start a native shell") {
                    harness.onMain { activity ->
                        activity.termuxService != null &&
                            activity.currentSession?.let { it.pid > 0 && it.emulator != null } == true
                    }
                }
            } catch (t: Throwable) {
                scenario.close()
                throw t
            }
            return harness
        }
    }

    fun <T> onMain(block: (TermuxActivity) -> T): T {
        val value = AtomicReference<T>()
        scenario.onActivity { value.set(block(it)) }
        return value.get()
    }

    fun service(): TermuxService = onMain { it.termuxService!! }

    fun sessionCount(): Int = onMain { it.termuxService!!.termuxSessionsSize }

    fun isListed(session: TerminalSession): Boolean = onMain { it.termuxService!!.getIndexOfSession(session) >= 0 }

    fun current(): TerminalSession? = onMain { it.currentSession }

    fun switchTo(session: TerminalSession) {
        onMain { it.termuxTerminalSessionClient.setCurrentSession(session) }
        waitUntil("Session switch did not take effect") { current() === session }
    }

    /**
     * Opens a dedicated session through the same path as the drawer's "New session" button, waits
     * for its PTY, and puts it in a known state: canonical echo, signals on, bracketed paste off.
     */
    fun openSession(name: String): TerminalSession {
        val previous = current()
        val created = onMain { activity ->
            activity.termuxTerminalSessionClient.addNewSession(false, name)
            activity.currentSession
        }
        assertNotNull("Dedicated session was not created", created)
        assertNotSame("Test must use its own terminal session", previous, created)
        val session = created!!
        owned += session
        waitUntil("Dedicated PTY did not initialize") { onMain { session.pid > 0 && session.emulator != null } }
        val ready = newNonce()
        run(session, "stty echo icanon isig; printf '\\033[?2004l'; ${printMarker("READY", ready)}")
        awaitMarker(session, "READY", ready)
        return session
    }

    fun transcript(session: TerminalSession): String =
        onMain { session.emulator?.screen?.transcriptText ?: "" }

    /** Writes raw bytes (UTF-8) to the PTY master, as TerminalView does for typed keys. */
    fun write(session: TerminalSession, text: String) = onMain { session.write(text) }

    /** Types [command] followed by a carriage return, as the Enter key does. */
    fun run(session: TerminalSession, command: String) = write(session, command + "\r")

    fun awaitMarker(session: TerminalSession, tag: String, nonce: String, timeoutMs: Long = TIMEOUT_MS,
                    message: String = "Marker $tag did not appear") {
        val needle = marker(tag, nonce)
        withTranscript(session) { waitUntil(message, timeoutMs) { transcript(session).contains(needle) } }
    }

    /**
     * Appends the end of the session's transcript to a failure, so CI shows what the shell printed.
     * Transcripts here hold only test fixtures and nonce markers, never user input.
     */
    fun <T> withTranscript(session: TerminalSession, block: () -> T): T = try {
        block()
    } catch (failure: AssertionError) {
        val tail = transcript(session).trimEnd().lines().takeLast(TRANSCRIPT_TAIL_LINES).joinToString("\n")
        throw AssertionError("${failure.message}\n--- transcript tail ---\n$tail", failure)
    }

    /** Emulator rows and columns, read on the main thread. */
    fun emulatorSize(session: TerminalSession): Pair<Int, Int> = onMain { session.emulator.mRows to session.emulator.mColumns }

    /**
     * Waits until the view stops resizing the session (for example while the soft keyboard animates
     * in) and returns the size it settled on.
     */
    fun awaitStableSize(session: TerminalSession, quietMs: Long = 1_000): Pair<Int, Int> {
        var last = emulatorSize(session)
        var since = SystemClock.uptimeMillis()
        val deadline = since + TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(50)
            val now = emulatorSize(session)
            if (now != last) { last = now; since = SystemClock.uptimeMillis() }
            else if (SystemClock.uptimeMillis() - since >= quietMs) return now
        }
        fail("Terminal size did not settle")
        throw AssertionError()
    }

    fun valueOrNull(session: TerminalSession, tag: String, nonce: String): String? {
        val pattern = Regex(Regex.escape(marker(tag, nonce) + ":") + "(.*?):END")
        return pattern.find(transcript(session))?.groupValues?.get(1)
    }

    fun awaitValue(session: TerminalSession, tag: String, nonce: String, timeoutMs: Long = TIMEOUT_MS,
                   message: String = "Value $tag was not printed"): String {
        var value: String? = null
        withTranscript(session) { waitUntil(message, timeoutMs) { valueOrNull(session, tag, nonce)?.also { value = it } != null } }
        return value!!
    }

    /** Runs a printf of [valueExpr] in the shell and returns what it printed. */
    fun capture(session: TerminalSession, tag: String, valueExpr: String): String {
        val nonce = newNonce()
        run(session, printValue(tag, nonce, valueExpr))
        return awaitValue(session, tag, nonce, message = "Shell did not report $tag")
    }

    /**
     * After an interrupt, proves the shell reads commands again and returns its `$$`. The probe is
     * re-sent every two seconds because a line arriving while the line discipline handles ^C may be
     * flushed with the interrupted input; it can only run once the foreground job has ended.
     */
    fun awaitShellPrompt(session: TerminalSession, withinMs: Long, message: String): String {
        val nonce = newNonce()
        val probe = printValue("ALIVE", nonce, "\"\$\$\"")
        val deadline = SystemClock.uptimeMillis() + withinMs
        while (SystemClock.uptimeMillis() < deadline) {
            run(session, probe)
            val retry = minOf(deadline, SystemClock.uptimeMillis() + 2_000)
            while (SystemClock.uptimeMillis() < retry) {
                valueOrNull(session, "ALIVE", nonce)?.let { return it }
                SystemClock.sleep(25)
            }
        }
        fail(message)
        throw AssertionError(message)
    }

    // ---------------------------------------------------------------- NeoTerm input panel

    fun selectMode(id: Int) = onMain { it.findViewById<RadioGroup>(R.id.neoterm_modes).check(id) }

    fun editor(activity: TermuxActivity): CompositionEditText = activity.findViewById(R.id.neoterm_draft)

    /** Commits [text] into the draft through a fresh InputConnection, as an IME does. */
    fun commitDraft(text: String): InputConnection = onMain { activity ->
        val editor = editor(activity)
        assertTrue("Draft editor must be composing", editor.engine.isComposing)
        val connection = editor.onCreateInputConnection(EditorInfo())
        assertNotNull("Composing editor must accept an InputConnection", connection)
        assertTrue("IME commit was rejected", connection!!.commitText(text, 1))
        assertTrue("Draft does not hold exactly the committed text", editor.text.toString() == text)
        connection
    }

    fun draft(): String = onMain { editor(it).text.toString() }

    fun status(): String = onMain { it.findViewById<TextView>(R.id.neoterm_draft_status).text.toString() }

    fun click(id: Int) = onMain { assertTrue("View click had no listener", it.findViewById<View>(id).performClick()) }

    override fun close() {
        try {
            for (session in owned.asReversed()) {
                onMain { session.finishIfRunning() }
                waitUntil("Dedicated shell did not stop") { onMain { !session.isRunning } }
                onMain { activity ->
                    val service = activity.termuxService
                    if (service != null && service.getIndexOfSession(session) >= 0)
                        activity.termuxTerminalSessionClient.removeFinishedSession(session)
                }
            }
        } finally {
            scenario.close()
        }
    }
}
