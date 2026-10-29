// SPDX-License-Identifier: GPL-3.0-only
package com.neoterm.input

import android.content.ComponentName
import android.content.Intent
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.RadioButton
import android.widget.TextView
import com.termux.R
import com.termux.app.TermuxActivity
import com.termux.app.TermuxService
import com.termux.shared.shell.TermuxSession
import com.termux.shared.models.ExecutionCommand
import com.termux.terminal.SessionFixture
import org.robolectric.RuntimeEnvironment
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class InputFocusTest {
    @Before fun bindQueueOnlyService() {
        // Real service/session objects, but no JNI/Linux subprocess in JVM tests.
        val service = Robolectric.buildService(TermuxService::class.java).get()
        val constructor = TermuxSession::class.java.declaredConstructors.single()
        constructor.isAccessible = true
        service.termuxSessions.add(constructor.newInstance(SessionFixture.create(), ExecutionCommand(1), null, false) as TermuxSession)
        shadowOf(RuntimeEnvironment.application).setComponentNameAndServiceForBindService(
            ComponentName(RuntimeEnvironment.application, TermuxService::class.java), service.onBind(Intent()))
    }

    @Test fun resumeAndDelayedKeyboardCallbacksKeepHardwareInputInComposer() {
        val controller = Robolectric.buildActivity(TermuxActivity::class.java).create().start().resume()
        val activity = controller.get()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        activity.findViewById<RadioButton>(R.id.neoterm_mode_text).performClick()
        val editor = activity.findViewById<CompositionEditText>(R.id.neoterm_draft)
        editor.setText("local")
        editor.setSelection(editor.length())
        assertTrue(editor.hasFocus())
        controller.pause().resume()
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertTrue("Resume stole composer focus", editor.hasFocus())
        assertFalse(activity.terminalView.hasFocus())
        assertEquals(InputMode.TEXT, editor.engine.mode)
        // No Android ViewRoot/PTY is attached in this JVM fixture. Dispatch through the
        // activity's actual focused child, which is where hardware input is delivered.
        val focused = activity.window.decorView.findFocus()
        assertSame(editor, focused)
        focused.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        focused.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        assertEquals("local\n", editor.text.toString())
        assertEquals("", SessionFixture.read(activity.currentSession))
        activity.findViewById<RadioButton>(R.id.neoterm_mode_terminal).performClick()
        assertTrue(activity.terminalView.hasFocus())
        controller.pause().stop().destroy()
    }
    @Test fun uiTransmissionStagesCorrectedParagraphAndRestoresUnsentLabel() {
        val controller = Robolectric.buildActivity(TermuxActivity::class.java).create().start().resume()
        val activity = controller.get()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        activity.findViewById<RadioButton>(R.id.neoterm_mode_text).performClick()
        val status = activity.findViewById<TextView>(R.id.neoterm_draft_status)
        val editor = activity.findViewById<CompositionEditText>(R.id.neoterm_draft)
        val ic = editor.onCreateInputConnection(EditorInfo())!!
        ic.setComposingText("Please explain teh failure.", 1)
        ic.setComposingText("Please explain the failure. Suggest a correction and describe its risks.", 1)
        ic.finishComposingText()
        assertEquals("", SessionFixture.read(activity.currentSession))
        activity.findViewById<View>(R.id.neoterm_transmit).performClick()
        assertEquals("Please explain the failure. Suggest a correction and describe its risks.",
            SessionFixture.read(activity.currentSession))
        assertEquals("", editor.text.toString())
        assertEquals(activity.getString(R.string.neoterm_transmitted), status.text.toString())
        assertFalse(ic.commitText("late duplicate", 1))
        editor.onCreateInputConnection(EditorInfo())!!.commitText("new unsent text", 1)
        assertEquals(activity.getString(R.string.neoterm_draft_label), status.text.toString())
        activity.findViewById<View>(R.id.neoterm_enter).performClick()
        assertEquals("", SessionFixture.read(activity.currentSession))
        activity.findViewById<View>(R.id.neoterm_clear).performClick()
        activity.findViewById<View>(R.id.neoterm_enter).performClick()
        assertEquals("\r", SessionFixture.read(activity.currentSession))
        controller.pause().stop().destroy()
    }
}
