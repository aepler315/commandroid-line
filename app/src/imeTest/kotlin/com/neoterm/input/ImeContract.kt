// SPDX-License-Identifier: GPL-3.0-only
package com.neoterm.input

import android.content.Context
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Shared contract: runs against real Android Editable on JVM and on-device. */
abstract class ImeContract {
    protected abstract fun context(): Context
    private lateinit var editor: CompositionEditText
    private lateinit var ic: InputConnection
    private lateinit var info: EditorInfo
    private val writes = mutableListOf<String>()
    private val target = object : TerminalTarget {
        override val identity = "ime-session"
        override val running = true
        override val bracketedPaste = true
        override fun write(text: String) { writes.add(text) }
    }
    @Before fun setup() {
        writes.clear()
        editor = CompositionEditText(context())
        editor.engine.bindSession(target.identity)
        editor.engine.setMode(InputMode.TEXT)
        editor.requestFocus()
        info = EditorInfo()
        ic = editor.onCreateInputConnection(info)!!
    }
    private fun value() = editor.text.toString()

    @Test fun compositionUpdatesReplaceRatherThanAppend() {
        ic.setComposingText("h", 1); ic.setComposingText("hel", 1); ic.setComposingText("hello", 1)
        assertEquals("hello", value())
        assertEquals(InputPhase.COMPOSING, editor.engine.phase)
        ic.finishComposingText()
        assertEquals(InputPhase.EDITING, editor.engine.phase)
        assertTrue(writes.isEmpty())
        assertEquals(SendResult.SENT, editor.engine.transmit(target))
        assertEquals(listOf("\u001b[200~hello\u001b[201~"), writes)
    }
    @Test fun autocorrectCanReplaceLocallyCommittedWord() {
        ic.commitText("teh cat", 1)
        ic.setComposingRegion(0, 3); ic.commitText("the", 1)
        assertEquals("the cat", value()); assertTrue(writes.isEmpty())
    }
    @Test fun swipeCommitIsStagedAsWholePhrase() {
        ic.commitText("Please explain this complex failure.", 1)
        assertEquals("Please explain this complex failure.", value())
        assertTrue(writes.isEmpty())
    }
    @Test fun cancellationRemovesOnlyComposition() {
        ic.commitText("prefix ", 1); ic.setComposingText("cancel", 1); ic.setComposingText("", 1)
        assertEquals("prefix ", value()); assertTrue(writes.isEmpty())
    }
    @Test fun backspaceOverCompositionNeverTouchesTerminal() {
        ic.setComposingText("cat", 1)
        ic.setComposingText("ca", 1)
        assertEquals("ca", value()); assertTrue(writes.isEmpty())
    }
    @Test fun backspaceOverLocallyCommittedTextNeverTouchesTerminal() {
        ic.commitText("cats", 1); ic.deleteSurroundingText(1, 0)
        assertEquals("cat", value()); assertTrue(writes.isEmpty())
    }
    @Test fun backspaceAfterTransmitCannotRewriteTerminal() {
        ic.commitText("sent", 1); editor.engine.transmit(target)
        val fresh = editor.onCreateInputConnection(EditorInfo())!!
        fresh.deleteSurroundingText(1, 0)
        assertEquals("", value())
        assertEquals(listOf("\u001b[200~sent\u001b[201~"), writes)
    }
    @Test fun cursorAndSelectionUseNativeEditable() {
        ic.commitText("alpha beta", 1); ic.setSelection(6, 10); ic.commitText("world", 1)
        assertEquals("alpha world", value())
        assertEquals(11, editor.selectionStart)
        ic.setSelection(0, 0); ic.setComposingText("Hi ", 1); ic.finishComposingText()
        assertEquals("Hi alpha world", value()); assertTrue(writes.isEmpty())
    }
    @Test fun cursorMovementDuringCompositionDoesNotTransmit() {
        ic.setComposingText("cat", 1); ic.setSelection(0, 0); ic.finishComposingText()
        ic.commitText("a ", 1)
        assertEquals("a cat", value()); assertTrue(writes.isEmpty())
    }
    @Test fun cursorPositionsFollowAndroidCommitContract() {
        ic.commitText("abcd", 1); ic.setSelection(2, 2); ic.commitText("XY", 0)
        assertEquals("abXYcd", value()); assertEquals(2, editor.selectionStart)
    }
    @Test fun utf16DeleteNeverLeavesHalfAnEmoji() {
        ic.commitText("a🙂", 1); ic.deleteSurroundingText(1, 0)
        assertEquals("a", value())
    }
    @Test fun forwardUtf16DeleteNeverLeavesHalfAnEmoji() {
        ic.commitText("🙂b", 1); ic.setSelection(0, 0); ic.deleteSurroundingText(0, 1)
        assertEquals("b", value())
    }
    @Test fun codePointDeleteAndCombiningTextArePreserved() {
        ic.commitText("e\u0301🙂中文", 1)
        ic.deleteSurroundingTextInCodePoints(3, 0)
        assertEquals("e\u0301", value()); assertTrue(writes.isEmpty())
    }
    @Test fun multilineAndImeActionsNeverSubmit() {
        ic.commitText("first\nsecond", 1)
        ic.performEditorAction(EditorInfo.IME_ACTION_SEND)
        ic.performEditorAction(EditorInfo.IME_ACTION_DONE)
        assertEquals("first\nsecond", value()); assertTrue(writes.isEmpty())
    }
    @Test fun hardwareEnterAddsLocalNewline() {
        ic.commitText("first", 1)
        editor.onKeyDown(KeyEvent.KEYCODE_ENTER, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        assertEquals("first\n", value()); assertTrue(writes.isEmpty())
    }
    @Test fun oldConnectionCannotRepopulateTransmittedText() {
        ic.commitText("once", 1); editor.engine.transmit(target)
        assertFalse(ic.commitText("once", 1))
        assertEquals("", value()); assertEquals(1, writes.size)
    }
    @Test fun sessionSwitchRejectsOldComposition() {
        ic.setComposingText("old", 1); editor.engine.bindSession("another")
        assertFalse(ic.commitText("late", 1))
        assertEquals("", value()); assertTrue(writes.isEmpty())
    }
    @Test fun rapidBatchCallbacksMaintainSingleComposition() {
        ic.beginBatchEdit()
        repeat(300) { ic.setComposingText("word$it", 1) }
        ic.commitText("final", 1); ic.endBatchEdit()
        assertEquals("final", value()); assertTrue(writes.isEmpty())
        assertEquals(-1, BaseInputConnection.getComposingSpanStart(editor.text))
    }
    @Test fun draftCannotBeSavedOrAutofilledAndRequestsPrivacy() {
        assertFalse(editor.isSaveEnabled)
        assertEquals(android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS, editor.importantForAutofill)
        assertTrue(info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)
        assertTrue(info.inputType and android.text.InputType.TYPE_TEXT_FLAG_AUTO_CORRECT != 0)
    }
}
