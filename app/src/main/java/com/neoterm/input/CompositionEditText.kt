// SPDX-License-Identifier: GPL-3.0-only
package com.neoterm.input

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.Selection
import android.util.AttributeSet
import android.view.Gravity
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.CompletionInfo
import android.view.inputmethod.CorrectionInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.widget.EditText
import kotlin.math.max
import kotlin.math.min

/** Native Editable is the sole owner of text, selection, and composing spans. */
// TermuxActivity is a platform Activity, so use its native widget/theme contract.
@SuppressLint("AppCompatCustomView")
class CompositionEditText @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : EditText(context, attrs) {
    private var finishActiveBatches: (() -> Unit)? = null

    val engine = InputEngine(object : Draft {
        override val text: String get() = this@CompositionEditText.text.toString()
        override val composing: Boolean get() = BaseInputConnection.getComposingSpanStart(this@CompositionEditText.text) >= 0
        override fun finishComposition() {
            finishActiveBatches?.invoke()
            BaseInputConnection.removeComposingSpans(this@CompositionEditText.text)
        }
        override fun clear() {
            finishComposition()
            this@CompositionEditText.text.clear()
            setSelection(0)
        }
    })

    init {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
        imeOptions = EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_ENTER_ACTION or
            EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        gravity = Gravity.TOP or Gravity.START
        isSaveEnabled = false
        isSaveFromParentEnabled = false
        setFreezesText(false)
        if (Build.VERSION.SDK_INT >= 26)
            importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        finishActiveBatches?.invoke()
        if (!engine.isComposing) return null
        val delegate = super.onCreateInputConnection(outAttrs) ?: return null
        val connection = engine.newConnection()
        return object : InputConnectionWrapper(delegate, false) {
            private var ownedBatches = 0
            private var closed = false
            private fun edit(operation: () -> Boolean) = !closed && engine.applyImeEdit(connection, operation)
            private fun drainBatches() {
                while (ownedBatches > 0) {
                    super.endBatchEdit()
                    ownedBatches--
                }
            }
            init { finishActiveBatches = { drainBatches() } }

            // Preserve Android's cursor-position contract even on older EditableInputConnection
            // implementations whose POINT selection spans move to the end of an insertion.
            private fun replace(value: CharSequence?, cursor: Int, composing: Boolean): Boolean = edit {
                val a = BaseInputConnection.getComposingSpanStart(text)
                val b = BaseInputConnection.getComposingSpanEnd(text)
                val start = if (a >= 0 && b >= 0) min(a, b) else min(selectionStart, selectionEnd).coerceAtLeast(0)
                val end = if (a >= 0 && b >= 0) max(a, b) else max(selectionStart, selectionEnd).coerceAtLeast(0)
                val oldLength = text.length
                val result = if (composing) super.setComposingText(value, cursor) else super.commitText(value, cursor)
                if (result) {
                    val inserted = text.length - oldLength + end - start
                    val position = if (cursor > 0) start.toLong() + inserted + cursor - 1 else start.toLong() + cursor
                    Selection.setSelection(text, position.coerceIn(0, text.length.toLong()).toInt())
                }
                result
            }

            override fun commitText(value: CharSequence?, newCursorPosition: Int) = replace(value, newCursorPosition, false)
            override fun setComposingText(value: CharSequence?, newCursorPosition: Int) = replace(value, newCursorPosition, true)
            override fun finishComposingText() = edit { super.finishComposingText() }
            override fun setComposingRegion(start: Int, end: Int) = edit {
                if (!boundary(start) || !boundary(end)) false else super.setComposingRegion(start, end)
            }
            override fun setSelection(start: Int, end: Int) = edit {
                if (!boundary(start) || !boundary(end)) false else super.setSelection(start, end)
            }
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int) = edit {
                if (beforeLength < 0 || afterLength < 0) return@edit false
                var start = min(selectionStart, selectionEnd)
                var end = max(selectionStart, selectionEnd)
                if (start < 0) return@edit false
                val a = BaseInputConnection.getComposingSpanStart(text)
                val b = BaseInputConnection.getComposingSpanEnd(text)
                if (a >= 0 && b >= 0) { start = min(start, min(a, b)); end = max(end, max(a, b)) }
                var left = (start.toLong() - beforeLength).coerceAtLeast(0).toInt()
                var right = (end.toLong() + afterLength).coerceAtMost(text.length.toLong()).toInt()
                if (!boundary(left)) left--
                if (!boundary(right)) right++
                super.deleteSurroundingText(start - left, right - end)
            }
            override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int) = edit {
                if (beforeLength < 0 || afterLength < 0) false
                else super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
            }
            override fun beginBatchEdit() = edit {
                super.beginBatchEdit().also { if (it) ownedBatches++ }
            }
            // Cleanup may outlive the generation, but must balance only this connection's batches.
            override fun endBatchEdit(): Boolean {
                if (ownedBatches == 0) return false
                ownedBatches--
                return super.endBatchEdit()
            }
            override fun performContextMenuAction(id: Int) = edit { super.performContextMenuAction(id) }
            override fun commitCompletion(value: CompletionInfo?) = edit { super.commitCompletion(value) }
            override fun commitCorrection(value: CorrectionInfo?) = edit { super.commitCorrection(value) }
            override fun clearMetaKeyStates(states: Int) = edit { super.clearMetaKeyStates(states) }
            override fun performPrivateCommand(action: String?, data: Bundle?) = edit { super.performPrivateCommand(action, data) }
            override fun sendKeyEvent(event: KeyEvent) = edit { super.sendKeyEvent(event) }
            // Suggestion action buttons may finish local composition; never submit to PTY.
            override fun performEditorAction(actionCode: Int) = edit { super.finishComposingText(); true }
            override fun closeConnection() {
                drainBatches()
                // A stale delegate close would remove the fresh connection's composing spans.
                edit { super.closeConnection(); true }
                closed = true
            }
        }
    }

    // Hardware Enter belongs to the local paragraph editor, never the terminal.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ENTER && !event.isCtrlPressed && !event.isAltPressed) {
            if (engine.isComposing) {
                BaseInputConnection.removeComposingSpans(text)
                val start = min(selectionStart, selectionEnd).coerceAtLeast(0)
                val end = max(selectionStart, selectionEnd).coerceAtLeast(0)
                text.replace(start, end, "\n")
                setSelection(start + 1)
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        if (keyCode == KeyEvent.KEYCODE_ENTER && !event.isCtrlPressed && !event.isAltPressed) true
        else super.onKeyUp(keyCode, event)

    private fun boundary(position: Int): Boolean = position in 0..text.length &&
        !(position > 0 && position < text.length && text[position - 1].isHighSurrogate() && text[position].isLowSurrogate())
}
