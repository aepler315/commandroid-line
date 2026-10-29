// SPDX-License-Identifier: GPL-3.0-only
package com.neoterm.input

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class CompositionLifecycleTest {
    private fun editor() = CompositionEditText(RuntimeEnvironment.application).also {
        it.engine.bindSession("a"); it.engine.setMode(InputMode.TEXT); it.requestFocus()
    }
    private val target = object : TerminalTarget {
        override val identity = "a"
        override val running = true
        override val bracketedPaste = false
        override fun write(text: String) {}
    }
    private fun nesting(ic: InputConnection): Int {
        val targetField = InputConnectionWrapper::class.java.getDeclaredField("mTarget")
        targetField.isAccessible = true
        val delegate = targetField.get(ic)
        val field = delegate.javaClass.getDeclaredField("mBatchEditNesting")
        field.isAccessible = true
        return field.getInt(delegate)
    }
    @Test fun staleCloseDrainsOnlyItsOwnBatchesAndPreservesFreshComposition() {
        for (transition in listOf("transmit", "clear", "mode", "session", "connection")) {
            val editor = editor()
            val old = editor.onCreateInputConnection(EditorInfo())!!
            old.beginBatchEdit(); old.beginBatchEdit(); old.commitText("old", 1)
            assertEquals(2, nesting(old))
            when (transition) {
                "transmit" -> editor.engine.transmit(target)
                "clear" -> editor.engine.discardDraft()
                "mode" -> { editor.engine.setMode(InputMode.TERMINAL); editor.engine.setMode(InputMode.TEXT) }
                "session" -> editor.engine.bindSession("b")
            }
            val fresh = editor.onCreateInputConnection(EditorInfo())!!
            fresh.setComposingText("fresh", 1)
            val before = editor.text.toString()
            old.closeConnection()
            assertTrue("Old batch retained after $transition", nesting(old) <= 0)
            assertEquals(before, editor.text.toString())
            assertTrue(BaseInputConnection.getComposingSpanStart(editor.text) >= 0)
            assertFalse(old.commitText("late", 1))
        }
    }
    @Test fun staleEndBatchCanBalanceOutstandingNativeEditWithoutChangingText() {
        val editor = editor()
        val old = editor.onCreateInputConnection(EditorInfo())!!
        old.beginBatchEdit(); old.beginBatchEdit()
        editor.engine.discardDraft()
        editor.onCreateInputConnection(EditorInfo())!!.setComposingText("fresh", 1)
        old.endBatchEdit(); old.endBatchEdit()
        assertEquals(0, nesting(old))
        assertEquals("fresh", editor.text.toString())
        assertTrue(BaseInputConnection.getComposingSpanStart(editor.text) >= 0)
    }
    @Test fun staleMenuActionsCannotPasteCutOrSelectCurrentDraft() {
        val clipboard = RuntimeEnvironment.application.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("fixture", "STALE PASTE"))
        for (transition in listOf("transmit", "clear", "mode", "session", "connection")) {
            for (action in listOf(android.R.id.paste, android.R.id.cut, android.R.id.selectAll)) {
                val editor = editor()
                val old = editor.onCreateInputConnection(EditorInfo())!!
                old.commitText("old", 1)
                when (transition) {
                    "transmit" -> editor.engine.transmit(target)
                    "clear" -> editor.engine.discardDraft()
                    "mode" -> { editor.engine.setMode(InputMode.TERMINAL); editor.engine.setMode(InputMode.TEXT) }
                    "session" -> editor.engine.bindSession("b")
                }
                val fresh = editor.onCreateInputConnection(EditorInfo())!!
                fresh.setSelection(0, editor.length()); fresh.commitText("new draft", 1)
                fresh.setSelection(0, 3)
                assertFalse("Stale menu accepted: $transition/$action", old.performContextMenuAction(action))
                assertEquals("new draft", editor.text.toString())
                assertEquals(0, editor.selectionStart); assertEquals(3, editor.selectionEnd)
            }
        }
    }
    @Test fun currentMenuPasteRetainsNativeSelectionReplacement() {
        val clipboard = RuntimeEnvironment.application.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("fixture", "world"))
        val editor = editor()
        val ic = editor.onCreateInputConnection(EditorInfo())!!
        ic.commitText("hello there", 1); ic.setSelection(6, 11)
        assertTrue(ic.performContextMenuAction(android.R.id.paste))
        assertEquals("hello world", editor.text.toString())
    }
}
