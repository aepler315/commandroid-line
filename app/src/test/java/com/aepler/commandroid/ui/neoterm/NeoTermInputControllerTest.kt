// SPDX-License-Identifier: GPL-3.0-only
package com.aepler.commandroid.ui.neoterm

import android.app.Application
import android.os.Looper
import android.text.InputType
import android.text.Selection
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.TextAttribute
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NeoTermInputControllerTest {

    private class RecordingImeBridge : ImeBridge {
        val restarted = mutableListOf<View>()
        val shown = mutableListOf<View>()
        override fun restartInput(view: View) { restarted += view }
        override fun showSoftInput(view: View) { shown += view }
    }

    private lateinit var view: View
    private lateinit var bridge: RecordingImeBridge
    private lateinit var controller: NeoTermInputController
    private val bufferUpdates = mutableListOf<Pair<String, IntRange?>>()
    private val committedLines = mutableListOf<String>()
    private val interceptedKeys = mutableListOf<Int>()
    private var interceptControlKeys = false

    @Before
    fun setUp() {
        view = View(ApplicationProvider.getApplicationContext<Application>())
        bridge = RecordingImeBridge()
        controller = NeoTermInputController(
            inputViewProvider = { view },
            onStagedBufferChanged = { text, range -> bufferUpdates += text to range },
            onCommitLine = { committedLines += it },
            onControlKeyIntercepted = { keyCode, _ ->
                interceptedKeys += keyCode
                interceptControlKeys
            },
            imeBridge = bridge
        )
    }

    private fun idleMain() = shadowOf(Looper.getMainLooper()).idle()

    private fun connection(): NeoTermInputController.StagedInputConnection =
        controller.onCreateInputConnection(EditorInfo()) as NeoTermInputController.StagedInputConnection

    private fun composingRange(): IntRange? {
        val start = BaseInputConnection.getComposingSpanStart(controller.stagedEditable)
        val end = BaseInputConnection.getComposingSpanEnd(controller.stagedEditable)
        return if (start in 0 until end) start until end else null
    }

    private fun assertNoUnpairedSurrogates(step: String) {
        val text = controller.stagedText
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (Character.isHighSurrogate(c)) {
                assertTrue("$step: unpaired high surrogate at $i", i + 1 < text.length && Character.isLowSurrogate(text[i + 1]))
                i += 2
                continue
            }
            assertFalse("$step: unpaired low surrogate at $i", Character.isLowSurrogate(c))
            i++
        }
        val editable = controller.stagedEditable
        for (boundary in listOf(
            Selection.getSelectionStart(editable),
            Selection.getSelectionEnd(editable),
            BaseInputConnection.getComposingSpanStart(editable),
            BaseInputConnection.getComposingSpanEnd(editable)
        )) {
            if (boundary < 0) continue
            assertEquals("$step: boundary $boundary splits a pair", boundary,
                controller.clampToSurrogateSafeBoundary(text, boundary, preferStart = true))
        }
    }

    // --- Invariant 1 -----------------------------------------------------------------------------

    @Test
    fun sessionChangeRestoresStagedTextClearsComposingAndRestartsOnce() {
        controller.onSessionChanged("session-1")
        idleMain()
        val ic = connection()
        ic.setComposingText("dir", 1)
        assertEquals(0 until 3, composingRange())
        bridge.restarted.clear()

        controller.onSessionChanged("session-2", "cd ..")
        idleMain()

        assertEquals("session-2", controller.activeSessionId)
        assertEquals("cd ..", controller.stagedText)
        assertNull(composingRange())
        assertEquals(5, Selection.getSelectionStart(controller.stagedEditable))
        assertEquals(5, Selection.getSelectionEnd(controller.stagedEditable))
        assertEquals(listOf(view), bridge.restarted)
        assertEquals("cd .." to null, bufferUpdates.last())
        assertTrue(controller.isStagedTextMode)
    }

    @Test
    fun sameSessionIdIsNoOp() {
        controller.onSessionChanged("session-2", "ls")
        idleMain()
        bridge.restarted.clear()
        val updatesBefore = bufferUpdates.size

        controller.onSessionChanged("session-2", "something else")
        idleMain()

        assertEquals("ls", controller.stagedText)
        assertTrue(bridge.restarted.isEmpty())
        assertEquals(updatesBefore, bufferUpdates.size)
    }

    @Test
    fun sessionChangeFromBackgroundThreadRestartsOnceOnMain() {
        val worker = Thread { controller.onSessionChanged("session-3", "pwd") }
        worker.start()
        worker.join()
        assertTrue(bridge.restarted.isEmpty())

        idleMain()

        assertEquals("pwd", controller.stagedText)
        assertEquals(listOf(view), bridge.restarted)
    }

    // --- Invariant 2 -----------------------------------------------------------------------------

    @Test
    fun tapWithActiveCompositionShowsKeyboardWithoutFinishingComposition() {
        val ic = connection()
        ic.setComposingText("dir", 1)
        assertFalse(view.isFocused)

        controller.onTerminalTap()
        idleMain()

        assertTrue(view.isFocusable)
        assertTrue(view.isFocusableInTouchMode)
        assertTrue(view.isFocused)
        assertEquals(listOf(view), bridge.shown)
        assertEquals("dir", controller.stagedText)
        assertEquals(0 until 3, composingRange())
        assertTrue(controller.isStagedTextMode)
        assertTrue(bridge.restarted.isEmpty())

        controller.onTerminalTap()
        idleMain()
        assertEquals(2, bridge.shown.size)
        assertEquals(0 until 3, composingRange())
    }

    @Test
    fun inputConnectionAttributesFollowStagedMode() {
        val attrs = EditorInfo()
        val ic = controller.onCreateInputConnection(attrs)
        assertEquals(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
            attrs.inputType
        )
        assertEquals(EditorInfo.IME_ACTION_SEND, attrs.imeOptions and EditorInfo.IME_MASK_ACTION)
        assertTrue(attrs.imeOptions and EditorInfo.IME_FLAG_NO_EXTRACT_UI != 0)
        assertTrue(attrs.imeOptions and EditorInfo.IME_FLAG_NO_FULLSCREEN != 0)
        assertTrue((ic as BaseInputConnection).editable === controller.stagedEditable)

        controller.setStagedTextMode(false)
        idleMain()
        val rawAttrs = EditorInfo()
        controller.onCreateInputConnection(rawAttrs)
        assertEquals(InputType.TYPE_NULL, rawAttrs.inputType)
        assertEquals(1, bridge.restarted.size)
    }

    // --- Invariant 3 -----------------------------------------------------------------------------

    private fun stageRocketLine(ic: InputConnection) {
        ic.commitText("echo 🚀🔥", 1)
        assertEquals(9, controller.stagedText.length)
        assertNoUnpairedSurrogates("initial commit")
    }

    @Test
    fun surrogateSpecSequence() {
        val ic = connection()
        stageRocketLine(ic)

        assertTrue(ic.setComposingRegion(5, 6))
        assertNoUnpairedSurrogates("setComposingRegion(5,6)")
        assertEquals(5 until 7, composingRange())

        // Force the raw cursor between the rocket's surrogates, bypassing the guarded setSelection.
        Selection.setSelection(controller.stagedEditable, 6)
        assertTrue(ic.deleteSurroundingText(1, 0))
        assertNoUnpairedSurrogates("deleteSurroundingText(1,0)")

        ic.finishComposingText()
        controller.stagedEditable.replace(0, controller.stagedEditable.length, "echo 🚀🔥")
        assertTrue(ic.replaceText(5, 6, "x", 1, null))
        assertNoUnpairedSurrogates("replaceText(5,6,x,1,null)")
        assertEquals("echo x🔥", controller.stagedText)
        assertEquals(6, Selection.getSelectionStart(controller.stagedEditable))
    }

    @Test
    fun deleteSurroundingTextNeverSplitsPair() {
        val ic = connection()
        stageRocketLine(ic)
        Selection.setSelection(controller.stagedEditable, 7) // after rocket
        assertTrue(ic.deleteSurroundingText(1, 1))
        assertNoUnpairedSurrogates("deleteSurroundingText(1,1)")
        assertEquals("echo ", controller.stagedText)

        assertTrue(ic.deleteSurroundingText(100, Int.MAX_VALUE))
        assertEquals("", controller.stagedText)
        assertFalse(ic.deleteSurroundingText(-1, 0))
    }

    @Test
    fun deleteSurroundingTextInCodePointsRemovesWholeEmoji() {
        val ic = connection()
        stageRocketLine(ic)
        assertTrue(ic.deleteSurroundingTextInCodePoints(1, 0))
        assertNoUnpairedSurrogates("deleteSurroundingTextInCodePoints(1,0)")
        assertEquals("echo 🚀", controller.stagedText)

        Selection.setSelection(controller.stagedEditable, 6) // mid-pair
        assertTrue(ic.deleteSurroundingTextInCodePoints(2, 5))
        assertNoUnpairedSurrogates("deleteSurroundingTextInCodePoints(2,5)")
        assertEquals("ech", controller.stagedText)
    }

    @Test
    fun commitAndComposeOverloadsStaySurrogateSafe() {
        val ic = connection()
        stageRocketLine(ic)

        Selection.setSelection(controller.stagedEditable, 6)
        assertTrue(ic.commitText("A", 1))
        assertNoUnpairedSurrogates("commitText mid-pair")

        assertTrue(ic.setComposingText("😀", 2, TextAttribute.Builder().build()))
        assertNoUnpairedSurrogates("setComposingText(TextAttribute)")

        assertTrue(ic.setComposingText("ab", -1))
        assertNoUnpairedSurrogates("setComposingText negative cursor")

        assertTrue(ic.setComposingRegion(-5, 9999, null))
        assertNoUnpairedSurrogates("setComposingRegion(TextAttribute) out of range")
        assertEquals(0 until controller.stagedText.length, composingRange())

        assertTrue(ic.commitText("🔥", 1, null))
        assertNoUnpairedSurrogates("commitText(TextAttribute)")
        assertEquals("🔥", controller.stagedText)

        assertTrue(ic.setSelection(1, 1))
        assertNoUnpairedSurrogates("setSelection(1,1)")
        assertTrue(ic.setSelection(-3, 1))
        assertNoUnpairedSurrogates("setSelection(-3,1)")

        assertTrue(ic.replaceText(-4, 1, "z", 5, null))
        assertNoUnpairedSurrogates("replaceText out of range")
        assertEquals("z", controller.stagedText)
    }

    @Test
    fun hardwareDelDeletesWholeCodePoint() {
        val ic = connection()
        stageRocketLine(ic)
        assertTrue(ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)))
        assertTrue(ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL)))
        assertNoUnpairedSurrogates("DEL")
        assertEquals("echo 🚀", controller.stagedText)
    }

    // --- Line commit ---------------------------------------------------------------------------

    @Test
    fun enterCommitsStagedLineAndClearsBuffer() {
        val ic = connection()
        ic.commitText("dir /a", 1)
        ic.setComposingText("x", 1)

        assertTrue(ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)))
        assertTrue(ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER)))

        assertEquals(listOf("dir /ax"), committedLines)
        assertEquals("", controller.stagedText)
        assertNull(composingRange())
        assertEquals("" to null, bufferUpdates.last())
    }

    @Test
    fun newlineInCommitTextFlushesCompleteLines() {
        val ic = connection()
        ic.commitText("echo one\r\necho two\nech", 1)

        assertEquals(listOf("echo one", "echo two"), committedLines)
        assertEquals("ech", controller.stagedText)
        assertEquals(3, Selection.getSelectionStart(controller.stagedEditable))
    }

    @Test
    fun imeSendActionCommitsLine() {
        val ic = connection()
        ic.commitText("cls", 1)
        assertTrue(ic.performEditorAction(EditorInfo.IME_ACTION_SEND))
        assertEquals(listOf("cls"), committedLines)
        assertEquals("", controller.stagedText)
    }

    @Test
    fun interceptedControlKeyShortCircuits() {
        val ic = connection()
        ic.commitText("dir", 1)
        interceptControlKeys = true

        val handled = ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))

        assertTrue(handled)
        assertEquals(listOf(KeyEvent.KEYCODE_ENTER), interceptedKeys)
        assertTrue(committedLines.isEmpty())
        assertEquals("dir", controller.stagedText)

        assertTrue(ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)))
        assertEquals("dir", controller.stagedText)
    }

    @Test
    fun clampToSurrogateSafeBoundaryMovesOffPairInterior() {
        val text = "a🚀b"
        assertEquals(1, controller.clampToSurrogateSafeBoundary(text, 2, preferStart = true))
        assertEquals(3, controller.clampToSurrogateSafeBoundary(text, 2, preferStart = false))
        assertEquals(0, controller.clampToSurrogateSafeBoundary(text, -7, preferStart = false))
        assertEquals(4, controller.clampToSurrogateSafeBoundary(text, 99, preferStart = true))
        assertEquals(3, controller.clampToSurrogateSafeBoundary(text, 3, preferStart = true))
    }

    private fun down(keyCode: Int) = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)

    @Test
    fun hardwareKeysEditStagedBufferSurrogateSafely() {
        controller.onSessionChanged("s1", "a\uD83D\uDE80b")
        idleMain()
        assertTrue(controller.handleKeyEvent(down(KeyEvent.KEYCODE_DPAD_LEFT)))
        assertEquals(3, Selection.getSelectionEnd(controller.stagedEditable))
        assertTrue(controller.handleKeyEvent(down(KeyEvent.KEYCODE_DPAD_LEFT)))
        assertEquals(1, Selection.getSelectionEnd(controller.stagedEditable))
        assertTrue(controller.handleKeyEvent(down(KeyEvent.KEYCODE_X)))
        assertEquals("ax\uD83D\uDE80b", controller.stagedText)
        assertTrue(controller.handleKeyEvent(down(KeyEvent.KEYCODE_FORWARD_DEL)))
        assertEquals("axb", controller.stagedText)
        assertNoUnpairedSurrogates("hardware edit")
        assertTrue(controller.handleKeyEvent(down(KeyEvent.KEYCODE_ENTER)))
        assertEquals(listOf("axb"), committedLines)
        assertEquals("", controller.stagedText)
    }

    @Test
    fun handleKeyEventConsultsControlInterceptorFirst() {
        interceptControlKeys = true
        assertTrue(controller.handleKeyEvent(down(KeyEvent.KEYCODE_C)))
        assertEquals(listOf(KeyEvent.KEYCODE_C), interceptedKeys)
        assertEquals("", controller.stagedText)
    }

    @Test
    fun moveCursorClampsAndClearStagedTextDiscardsLine() {
        controller.onSessionChanged("s1", "\uD83D\uDE80x")
        idleMain()
        controller.moveCursor(-10)
        assertEquals(0, Selection.getSelectionEnd(controller.stagedEditable))
        controller.moveCursor(1)
        assertEquals(2, Selection.getSelectionEnd(controller.stagedEditable))
        controller.moveCursor(10)
        assertEquals(3, Selection.getSelectionEnd(controller.stagedEditable))
        controller.clearStagedText()
        assertEquals("", controller.stagedText)
        assertTrue(committedLines.isEmpty())
        assertEquals("" to null, bufferUpdates.last())
    }

    @Test
    fun commitTextInterceptorConsumesText() {
        controller.onCommitTextIntercepted = { it == "c" }
        val ic = connection()
        ic.commitText("c", 1)
        assertEquals("", controller.stagedText)
        ic.commitText("d", 1)
        assertEquals("d", controller.stagedText)
    }
}
