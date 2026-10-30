// SPDX-License-Identifier: GPL-3.0-only
package com.aepler.commandroid.ui.neoterm

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.Selection
import android.text.SpannableStringBuilder
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.TextAttribute
import androidx.annotation.MainThread
import androidx.annotation.RequiresApi
import androidx.annotation.VisibleForTesting

/**
 * Thin seam over [InputMethodManager] and main-thread dispatch so that IME restarts and soft-input
 * requests can be observed exactly in tests.
 */
interface ImeBridge {
    fun restartInput(view: View)

    fun showSoftInput(view: View)

    /** Runs [block] on the main thread: synchronously when already on it, otherwise posted. */
    fun postToMain(view: View, block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            Handler(Looper.getMainLooper()).post(block)
        }
    }
}

/** Default [ImeBridge] backed by the view's [InputMethodManager]. */
object SystemImeBridge : ImeBridge {
    private fun imm(view: View): InputMethodManager? =
        view.context.getSystemService(InputMethodManager::class.java)

    override fun restartInput(view: View) {
        imm(view)?.restartInput(view)
    }

    override fun showSoftInput(view: View) {
        imm(view)?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }
}

/**
 * Staged IME input controller (spec section 2).
 *
 * The IME edits [stagedEditable] through [StagedInputConnection]; nothing reaches the terminal until a
 * line is committed (Enter, IME send action, or a committed newline). All mutations clamp indices to
 * the buffer and never split a UTF-16 surrogate pair. Must be driven from the main thread; entry
 * points called elsewhere are re-dispatched through [ImeBridge.postToMain]. Never logs input text.
 */
class NeoTermInputController(
    private val inputViewProvider: () -> View,
    private val onStagedBufferChanged: (stagedText: String, composingRange: IntRange?) -> Unit,
    private val onCommitLine: (committedCommand: String) -> Unit,
    private val onControlKeyIntercepted: (keyCode: Int, event: KeyEvent) -> Boolean,
    private val imeBridge: ImeBridge = SystemImeBridge
) {
    @VisibleForTesting
    internal val stagedEditable: Editable = SpannableStringBuilder()

    @VisibleForTesting
    internal val stagedText: String
        get() = stagedEditable.toString()

    @VisibleForTesting
    internal var activeSessionId: String? = null
        private set

    /** True while the IME edits the staged buffer; false means raw key-event bypass (TYPE_NULL). */
    var isStagedTextMode: Boolean = true
        private set

    /** Incremented on every effective session switch so stale posted restarts can be dropped. */
    private var sessionGeneration = 0

    fun inputView(): View = inputViewProvider()

    /**
     * Invariant 1: switching sessions replaces the staged composition with [restoredStagedText] and
     * restarts the IME exactly once. Re-selecting the active session is a no-op.
     */
    fun onSessionChanged(newSessionId: String, restoredStagedText: String = "") {
        val view = inputView()
        imeBridge.postToMain(view) { applySessionChange(view, newSessionId, restoredStagedText) }
    }

    @MainThread
    private fun applySessionChange(view: View, newSessionId: String, restoredStagedText: String) {
        if (newSessionId == activeSessionId) return
        activeSessionId = newSessionId
        val generation = ++sessionGeneration

        BaseInputConnection.removeComposingSpans(stagedEditable)
        stagedEditable.replace(0, stagedEditable.length, restoredStagedText)
        Selection.setSelection(stagedEditable, stagedEditable.length)
        notifyStagedState()

        imeBridge.postToMain(view) {
            if (generation != sessionGeneration) return@postToMain
            if (view.isFocusable && !view.isFocused) view.requestFocus()
            imeBridge.restartInput(view)
        }
    }

    /**
     * Invariant 2: a single tap focuses the input view and shows the soft keyboard without finishing
     * the active composition and without leaving staged text mode.
     */
    fun onTerminalTap() {
        val view = inputView()
        imeBridge.postToMain(view) {
            if (!view.isFocusable) view.isFocusable = true
            if (!view.isFocusableInTouchMode) view.isFocusableInTouchMode = true
            if (!view.isFocused) view.requestFocus()
            imeBridge.showSoftInput(view)
        }
    }

    /** Switches between staged IME editing and raw key-event bypass, restarting the IME on change. */
    fun setStagedTextMode(enabled: Boolean) {
        val view = inputView()
        imeBridge.postToMain(view) {
            if (isStagedTextMode == enabled) return@postToMain
            isStagedTextMode = enabled
            if (!enabled) {
                BaseInputConnection.removeComposingSpans(stagedEditable)
                notifyStagedState()
            }
            imeBridge.restartInput(view)
        }
    }

    fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = if (isStagedTextMode) {
            InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        } else {
            InputType.TYPE_NULL
        }
        outAttrs.imeOptions = EditorInfo.IME_ACTION_SEND or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN
        outAttrs.initialSelStart = Selection.getSelectionStart(stagedEditable)
        outAttrs.initialSelEnd = Selection.getSelectionEnd(stagedEditable)
        return StagedInputConnection(inputView(), true)
    }

    /**
     * Coerces [rawIndex] into `[0, text.length]` and, if it falls between a high and a low surrogate,
     * moves it to the start ([preferStart]) or end of that code point.
     */
    @VisibleForTesting
    internal fun clampToSurrogateSafeBoundary(text: CharSequence, rawIndex: Int, preferStart: Boolean): Int {
        val index = rawIndex.coerceIn(0, text.length)
        if (index <= 0 || index >= text.length) return index
        return if (Character.isHighSurrogate(text[index - 1]) && Character.isLowSurrogate(text[index])) {
            if (preferStart) index - 1 else index + 1
        } else {
            index
        }
    }

    private fun notifyStagedState() {
        val start = BaseInputConnection.getComposingSpanStart(stagedEditable)
        val end = BaseInputConnection.getComposingSpanEnd(stagedEditable)
        val range = if (start in 0 until end) start until end else null
        onStagedBufferChanged(stagedEditable.toString(), range)
    }

    /** Commits every complete line (up to each '\n', trailing '\r' trimmed); keeps the remainder staged. */
    private fun flushLineIfComplete() {
        var newline = indexOfNewline()
        if (newline < 0) return
        val lines = ArrayList<String>()
        while (newline >= 0) {
            lines += stagedEditable.subSequence(0, newline).toString().trimEnd('\r')
            val cursor = Selection.getSelectionEnd(stagedEditable)
            BaseInputConnection.removeComposingSpans(stagedEditable)
            stagedEditable.delete(0, newline + 1)
            val shifted = (cursor - (newline + 1)).coerceIn(0, stagedEditable.length)
            Selection.setSelection(stagedEditable, shifted)
            newline = indexOfNewline()
        }
        notifyStagedState()
        lines.forEach(onCommitLine)
    }

    private fun indexOfNewline(): Int {
        for (i in 0 until stagedEditable.length) if (stagedEditable[i] == '\n') return i
        return -1
    }

    /** Submits the whole staged line and clears the buffer. */
    private fun commitStagedLine() {
        val line = stagedEditable.toString().trimEnd('\r', '\n')
        BaseInputConnection.removeComposingSpans(stagedEditable)
        stagedEditable.clear()
        Selection.setSelection(stagedEditable, 0)
        notifyStagedState()
        onCommitLine(line)
    }

    /** Re-clamps selection and composing region of [stagedEditable] to surrogate-safe boundaries. */
    private fun sanitizeStagedRanges(connection: StagedInputConnection) {
        val text = stagedEditable
        val selStart = Selection.getSelectionStart(text)
        val selEnd = Selection.getSelectionEnd(text)
        if (selStart < 0 || selEnd < 0) {
            Selection.setSelection(text, text.length)
        } else {
            var a = minOf(selStart, selEnd).coerceIn(0, text.length)
            var b = maxOf(selStart, selEnd).coerceIn(0, text.length)
            if (a == b) {
                a = clampToSurrogateSafeBoundary(text, a, preferStart = true)
                b = a
            } else {
                a = clampToSurrogateSafeBoundary(text, a, preferStart = true)
                b = clampToSurrogateSafeBoundary(text, b, preferStart = false)
            }
            if (a != minOf(selStart, selEnd) || b != maxOf(selStart, selEnd)) Selection.setSelection(text, a, b)
        }

        val composeStart = BaseInputConnection.getComposingSpanStart(text)
        val composeEnd = BaseInputConnection.getComposingSpanEnd(text)
        if (composeStart >= 0 && composeEnd >= 0) {
            val a = clampToSurrogateSafeBoundary(text, minOf(composeStart, composeEnd), preferStart = true)
            val b = clampToSurrogateSafeBoundary(text, maxOf(composeStart, composeEnd), preferStart = false)
            if (a != composeStart || b != composeEnd) connection.applyComposingRegion(a, b)
        }
    }

    /**
     * InputConnection over [stagedEditable]. Every editing and deletion overload clamps its indices
     * to `[0, length]` and to surrogate-safe boundaries before mutating.
     */
    inner class StagedInputConnection(targetView: View, fullEditor: Boolean) :
        BaseInputConnection(targetView, fullEditor) {

        override fun getEditable(): Editable = stagedEditable

        internal fun applyComposingRegion(start: Int, end: Int) {
            super.setComposingRegion(start, end)
        }

        private fun sanitizeRangeBeforeComposingMutation() = sanitizeStagedRanges(this)

        override fun setSelection(start: Int, end: Int): Boolean {
            val text = stagedEditable
            val rawStart = start.coerceIn(0, text.length)
            val rawEnd = end.coerceIn(0, text.length)
            val safeStart: Int
            val safeEnd: Int
            if (rawStart == rawEnd) {
                safeStart = clampToSurrogateSafeBoundary(text, rawStart, preferStart = true)
                safeEnd = safeStart
            } else if (rawStart < rawEnd) {
                safeStart = clampToSurrogateSafeBoundary(text, rawStart, preferStart = true)
                safeEnd = clampToSurrogateSafeBoundary(text, rawEnd, preferStart = false)
            } else {
                // Reversed selections keep their anchor/extent orientation.
                safeStart = clampToSurrogateSafeBoundary(text, rawStart, preferStart = false)
                safeEnd = clampToSurrogateSafeBoundary(text, rawEnd, preferStart = true)
            }
            Selection.setSelection(text, safeStart, safeEnd)
            notifyStagedState()
            return true
        }

        override fun setComposingRegion(start: Int, end: Int): Boolean {
            val text = stagedEditable
            val a = minOf(start, end).coerceIn(0, text.length)
            val b = maxOf(start, end).coerceIn(0, text.length)
            val safeStart = clampToSurrogateSafeBoundary(text, a, preferStart = true)
            val safeEnd = clampToSurrogateSafeBoundary(text, b, preferStart = false)
            super.setComposingRegion(safeStart, safeEnd)
            sanitizeStagedRanges(this)
            notifyStagedState()
            return true
        }

        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        override fun setComposingRegion(start: Int, end: Int, textAttribute: TextAttribute?): Boolean =
            setComposingRegion(start, end)

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
            sanitizeRangeBeforeComposingMutation()
            super.setComposingText(text ?: "", newCursorPosition)
            sanitizeStagedRanges(this)
            notifyStagedState()
            return true
        }

        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        override fun setComposingText(
            text: CharSequence,
            newCursorPosition: Int,
            textAttribute: TextAttribute?
        ): Boolean = setComposingText(text, newCursorPosition)

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            sanitizeRangeBeforeComposingMutation()
            super.commitText(text ?: "", newCursorPosition)
            sanitizeStagedRanges(this)
            notifyStagedState()
            flushLineIfComplete()
            return true
        }

        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        override fun commitText(
            text: CharSequence,
            newCursorPosition: Int,
            textAttribute: TextAttribute?
        ): Boolean = commitText(text, newCursorPosition)

        @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        override fun replaceText(
            start: Int,
            end: Int,
            text: CharSequence,
            newCursorPosition: Int,
            textAttribute: TextAttribute?
        ): Boolean {
            val content = stagedEditable
            val a = clampToSurrogateSafeBoundary(content, minOf(start, end), preferStart = true)
            val b = clampToSurrogateSafeBoundary(content, maxOf(start, end), preferStart = false)
            removeComposingSpans(content)
            content.replace(a, b, text)
            // Same semantics as commitText: > 0 is relative to the end of the inserted text (1 = just
            // after it), <= 0 is relative to its start.
            val insertedEnd = a + text.length
            val rawCursor = if (newCursorPosition > 0) {
                insertedEnd.toLong() + newCursorPosition - 1
            } else {
                a.toLong() + newCursorPosition
            }
            val cursor = clampToSurrogateSafeBoundary(
                content,
                rawCursor.coerceIn(0L, content.length.toLong()).toInt(),
                preferStart = newCursorPosition <= 0
            )
            Selection.setSelection(content, cursor)
            notifyStagedState()
            flushLineIfComplete()
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            if (beforeLength < 0 || afterLength < 0) return false
            val (a, b) = protectedRange()
            val content = stagedEditable
            val deleteStart = clampToSurrogateSafeBoundary(
                content, (a.toLong() - beforeLength).coerceAtLeast(0L).toInt(), preferStart = true
            )
            val deleteEnd = clampToSurrogateSafeBoundary(
                content, (b.toLong() + afterLength).coerceAtMost(content.length.toLong()).toInt(), preferStart = false
            )
            deleteAround(deleteStart, a, b, deleteEnd)
            return true
        }

        override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
            if (beforeLength < 0 || afterLength < 0) return false
            val (a, b) = protectedRange()
            val content = stagedEditable
            var deleteStart = a
            repeat(beforeLength) {
                if (deleteStart <= 0) return@repeat
                deleteStart -= if (deleteStart >= 2 &&
                    Character.isLowSurrogate(content[deleteStart - 1]) &&
                    Character.isHighSurrogate(content[deleteStart - 2])
                ) 2 else 1
            }
            var deleteEnd = b
            repeat(afterLength) {
                if (deleteEnd >= content.length) return@repeat
                deleteEnd += if (deleteEnd + 1 < content.length &&
                    Character.isHighSurrogate(content[deleteEnd]) &&
                    Character.isLowSurrogate(content[deleteEnd + 1])
                ) 2 else 1
            }
            deleteAround(
                clampToSurrogateSafeBoundary(content, deleteStart, preferStart = true),
                a,
                b,
                clampToSurrogateSafeBoundary(content, deleteEnd, preferStart = false)
            )
            return true
        }

        /**
         * Selection extended to cover the composing region (BaseInputConnection semantics: surrounding
         * deletes never eat into the active composition), clamped to surrogate-safe boundaries.
         */
        private fun protectedRange(): Pair<Int, Int> {
            val content = stagedEditable
            val len = content.length
            var a = Selection.getSelectionStart(content)
            var b = Selection.getSelectionEnd(content)
            if (a < 0 || b < 0) {
                a = len
                b = len
            }
            if (a > b) a = b.also { b = a }
            val ca = getComposingSpanStart(content)
            val cb = getComposingSpanEnd(content)
            if (ca >= 0 && cb >= 0) {
                a = minOf(a, ca, cb)
                b = maxOf(b, ca, cb)
            }
            if (a == b) {
                // A collapsed cursor stays a single point: snap it before the code point it splits.
                a = clampToSurrogateSafeBoundary(content, a, preferStart = true)
                return a to a
            }
            a = clampToSurrogateSafeBoundary(content, a, preferStart = true)
            b = clampToSurrogateSafeBoundary(content, b, preferStart = false)
            return a to b
        }

        private fun deleteAround(deleteStart: Int, a: Int, b: Int, deleteEnd: Int) {
            val content = stagedEditable
            if (deleteEnd > b) content.delete(b, deleteEnd)
            if (deleteStart < a) content.delete(deleteStart, a)
            sanitizeStagedRanges(this)
            notifyStagedState()
        }

        override fun performEditorAction(editorAction: Int): Boolean {
            return when (editorAction) {
                EditorInfo.IME_ACTION_SEND, EditorInfo.IME_ACTION_DONE, EditorInfo.IME_ACTION_GO -> {
                    commitStagedLine()
                    true
                }
                else -> super.performEditorAction(editorAction)
            }
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            val isDown = event.action == KeyEvent.ACTION_DOWN
            if (isDown && onControlKeyIntercepted(event.keyCode, event)) return true
            when (event.keyCode) {
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (isDown) commitStagedLine()
                    return true
                }
                KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL -> {
                    if (stagedEditable.isEmpty()) return super.sendKeyEvent(event)
                    if (isDown) deleteOneUnit(forward = event.keyCode == KeyEvent.KEYCODE_FORWARD_DEL)
                    return true
                }
            }
            return super.sendKeyEvent(event)
        }

        private fun deleteOneUnit(forward: Boolean) {
            val content = stagedEditable
            val selStart = Selection.getSelectionStart(content)
            val selEnd = Selection.getSelectionEnd(content)
            if (selStart >= 0 && selEnd >= 0 && selStart != selEnd) {
                val a = clampToSurrogateSafeBoundary(content, minOf(selStart, selEnd), preferStart = true)
                val b = clampToSurrogateSafeBoundary(content, maxOf(selStart, selEnd), preferStart = false)
                removeComposingSpans(content)
                content.delete(a, b)
                Selection.setSelection(content, a)
                sanitizeStagedRanges(this)
                notifyStagedState()
                return
            }
            // A hardware DEL edits at the cursor, so settle any active composition first.
            removeComposingSpans(content)
            if (forward) deleteSurroundingTextInCodePoints(0, 1) else deleteSurroundingTextInCodePoints(1, 0)
        }
    }
}
