// SPDX-License-Identifier: GPL-3.0-only
package com.neoterm.input

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class InputEngineTest {
    private class Buffer : Draft {
        override var text = ""
        override var composing = false
        override fun finishComposition() { composing = false }
        override fun clear() { text = ""; composing = false }
    }
    private class Target : TerminalTarget {
        override var identity = "session-a"
        override var running = true
        override var bracketedPaste = false
        val writes = mutableListOf<String>()
        var fail = false
        override fun write(text: String) {
            if (fail) throw IllegalStateException("transport error")
            writes.add(text)
        }
    }
    private lateinit var draft: Buffer
    private lateinit var target: Target
    private lateinit var engine: InputEngine
    @Before fun setup() {
        draft = Buffer(); target = Target(); engine = InputEngine(draft)
        engine.bindSession(target.identity)
    }
    private fun text(value: String) { engine.setMode(InputMode.TEXT); draft.text = value }

    @Test fun autoStartsDirectAndRequiresExplicitUserIntent() {
        assertEquals(InputMode.AUTO, engine.mode)
        assertEquals(InputPhase.DIRECT, engine.phase)
        engine.openComposer()
        assertTrue(engine.isComposing)
        assertEquals(InputPhase.EMPTY, engine.phase)
    }
    @Test fun ordinaryCommandNeverGainsEnter() {
        text("echo hello")
        assertEquals(SendResult.SENT, engine.transmit(target))
        assertEquals(listOf("echo hello"), target.writes)
        assertEquals("", draft.text)
    }
    @Test fun pathsFlagsAndWhitespaceRemainExact() {
        val value = "  ./A\\ B/bin --no-cache --path=/tmp/CaseSensitive  "
        text(value); engine.transmit(target); assertEquals(listOf(value), target.writes)
    }
    @Test fun paragraphRemainsLocalUntilExplicitTransmission() {
        text("Please explain the failure, and suggest a correction. Thank you.")
        draft.composing = true
        assertEquals(InputPhase.COMPOSING, engine.phase)
        draft.finishComposition()
        assertEquals(InputPhase.EDITING, engine.phase)
        assertTrue(target.writes.isEmpty())
        engine.transmit(target)
        assertEquals(1, target.writes.size)
    }
    @Test fun callbackEditsDoNotWriteOrSubmit() {
        engine.setMode(InputMode.TEXT)
        val token = engine.newConnection()
        assertTrue(engine.applyImeEdit(token) { draft.text = "teh"; draft.composing = true; true })
        engine.applyImeEdit(token) { draft.text = "the"; draft.finishComposition(); true }
        assertEquals("the", draft.text)
        assertTrue(target.writes.isEmpty())
    }
    @Test fun terminalModeFreezesDraftWithoutTransmission() {
        text("unfinished"); draft.composing = true
        engine.setMode(InputMode.TERMINAL)
        assertEquals(InputPhase.DIRECT, engine.phase)
        assertFalse(draft.composing)
        assertEquals("unfinished", draft.text)
        assertEquals(SendResult.DIRECT, engine.transmit(target))
        assertTrue(target.writes.isEmpty())
        engine.setMode(InputMode.TEXT)
        assertEquals("unfinished", draft.text)
    }
    @Test fun modeChangesInvalidateOldConnections() {
        engine.setMode(InputMode.TEXT); val token = engine.newConnection()
        engine.setMode(InputMode.TERMINAL); engine.setMode(InputMode.TEXT)
        assertFalse(engine.applyImeEdit(token) { draft.text = "stale"; true })
        assertEquals("", draft.text)
    }
    @Test fun replacementConnectionInvalidatesPredecessor() {
        engine.setMode(InputMode.TEXT); val old = engine.newConnection(); engine.newConnection()
        assertFalse(engine.applyImeEdit(old) { draft.text = "stale"; true })
    }
    @Test fun transmissionInvalidatesLateImeCallbacksAndCannotDuplicate() {
        text("hello"); val old = engine.newConnection()
        engine.transmit(target)
        assertFalse(engine.applyImeEdit(old) { draft.text = "hello"; true })
        assertEquals(SendResult.EMPTY, engine.transmit(target))
        assertEquals(listOf("hello"), target.writes)
    }
    @Test fun multilineRequiresBracketedPasteAndPreservesLf() {
        text("first\nsecond\n")
        assertEquals(SendResult.BRACKETED_PASTE_REQUIRED, engine.transmit(target))
        assertEquals("first\nsecond\n", draft.text)
        assertTrue(target.writes.isEmpty())
        target.bracketedPaste = true
        assertEquals(SendResult.SENT, engine.transmit(target))
        assertEquals(listOf("\u001b[200~first\nsecond\n\u001b[201~"), target.writes)
    }
    @Test fun tabsCannotInvokeCompletionInUnbracketedInput() {
        text("hi\tthere")
        assertEquals(SendResult.BRACKETED_PASTE_REQUIRED, engine.transmit(target))
    }
    @Test fun emojiCombiningCharactersAndLanguagesPreserved() {
        val value = "🙂 👨‍👩‍👧‍👦 e\u0301 中文 日本語 العربية 한글"
        text(value); engine.transmit(target); assertEquals(listOf(value), target.writes)
    }
    @Test fun malformedSurrogatesNeverReachTerminal() {
        for (value in listOf("\ud83d", "x\udc00", "\ud83dx")) {
            text(value)
            assertEquals(SendResult.INVALID_UNICODE, engine.transmit(target))
        }
        assertTrue(target.writes.isEmpty())
    }
    @Test fun escapesCtrlCDEZAndCarriageReturnsRejectedInDraft() {
        for (value in listOf("\u001b[201~", "\u0003", "\u0004", "\u001a", "\r", "\u0000", "\u007f", "\u0085")) {
            text(value); target.bracketedPaste = true
            assertEquals(SendResult.UNSAFE_CONTROL, engine.transmit(target))
        }
        assertTrue(target.writes.isEmpty())
    }
    @Test fun explicitSubmissionIsSeparateAndRefusesUnsentDraft() {
        text("prompt")
        assertEquals(SendResult.DIRECT, engine.submit(target))
        assertTrue(target.writes.isEmpty())
        engine.transmit(target)
        assertEquals(SendResult.SENT, engine.submit(target))
        assertEquals(listOf("prompt", "\r"), target.writes)
    }
    @Test fun sessionSwitchClearsDraftAndAutoIntent() {
        engine.openComposer(); draft.text = "belongs to A"
        val token = engine.newConnection()
        engine.bindSession("session-b")
        assertEquals("", draft.text)
        assertEquals(InputPhase.DIRECT, engine.phase)
        assertFalse(engine.applyImeEdit(token) { draft.text = "stale"; true })
    }
    @Test fun sameSessionReconnectRetainsLocalDraftWithoutReplay() {
        text("local only"); engine.bindSession(target.identity)
        assertEquals("local only", draft.text); assertTrue(target.writes.isEmpty())
    }
    @Test fun newProcessStartsEmptyWithoutReplay() {
        text("not persisted")
        val restartedDraft = Buffer(); val restarted = InputEngine(restartedDraft)
        restarted.bindSession(target.identity)
        assertEquals(InputMode.AUTO, restarted.mode)
        assertEquals("", restartedDraft.text); assertTrue(target.writes.isEmpty())
    }
    @Test fun disconnectedOrWrongSessionCannotConsumeDraft() {
        text("retain")
        assertEquals(SendResult.DISCONNECTED, engine.transmit(null))
        target.running = false
        assertEquals(SendResult.DISCONNECTED, engine.transmit(target))
        target.running = true; target.identity = "other"
        assertEquals(SendResult.DISCONNECTED, engine.transmit(target))
        assertEquals("retain", draft.text)
    }
    @Test fun failedWriteKeepsDraftAndDoesNotAutoRetry() {
        text("retain"); target.fail = true
        assertEquals(SendResult.WRITE_FAILED, engine.transmit(target))
        assertEquals("retain", draft.text); assertTrue(target.writes.isEmpty())
    }
    @Test fun rapidCallbacksSerializeWithoutPrematureWrites() {
        engine.setMode(InputMode.TEXT); val token = engine.newConnection()
        repeat(1000) { i -> assertTrue(engine.applyImeEdit(token) { draft.text += i.toString(); true }) }
        val expected = draft.text
        assertTrue(target.writes.isEmpty())
        engine.transmit(target); assertEquals(listOf(expected), target.writes)
    }
    @Test fun diagnosticsDoNotContainPasswordText() {
        text("secret-password-never-log")
        assertFalse(engine.toString().contains(draft.text))
        assertFalse(engine.phase.toString().contains(draft.text))
    }
}
