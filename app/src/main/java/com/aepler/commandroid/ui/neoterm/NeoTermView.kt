// SPDX-License-Identifier: GPL-3.0-only
package com.aepler.commandroid.ui.neoterm

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.OverScroller
import androidx.annotation.VisibleForTesting
import com.aepler.commandroid.presentation.TerminalRenderedLine
import com.termux.R

/**
 * Canvas-rendered terminal viewport: a [TerminalRowBuffer] scrollback plus one staged prompt
 * overlay row drawn on the grid row after the last output row. No Compose in this render path.
 *
 * All methods must be called on the main thread.
 */
class NeoTermView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    /** Receives taps, InputConnection creation and key routing. */
    var inputController: NeoTermInputController? = null

    /** Invoked with (columns, rows) whenever the character grid geometry changes. */
    var onGridResized: ((columns: Int, rows: Int) -> Unit)? = null
        set(value) {
            field = value
            // Late subscribers still learn the current geometry once the view has been laid out.
            if (value != null && notifiedColumns > 0) value(notifiedColumns, notifiedRows)
        }

    private val renderer = TerminalRenderer(context)
    private val buffer = TerminalRowBuffer(TerminalRowBuffer.DEFAULT_COLUMNS, TerminalRowBuffer.MAX_SCROLLBACK_LINES)
    private val scroller = OverScroller(context)
    private val gestureDetector = GestureDetector(context, GestureListener())

    private var notifiedColumns = 0
    private var notifiedRows = 0

    /** Bookkeeping for the append-only fast path of [submitLines]. */
    private var submittedCount = 0
    private var lastSubmitted: TerminalRenderedLine? = null
    private var evictedSeen = 0L

    private var prompt = ""
    private var staged = ""
    private var composingStart = -1
    private var composingEnd = -1

    /** Pixel offset of the viewport top within the content; content = scrollback rows + prompt row. */
    private var scrollPx = 0
    private var followBottom = true

    /** Grid columns currently used for wrapping. */
    val columns: Int get() = buffer.columns

    /** Visible grid rows (0 before the first layout). */
    val rows: Int get() = notifiedRows

    val charWidthPx: Float get() = renderer.charWidth
    val lineHeightPx: Int get() = renderer.lineHeight

    /** Row of the prompt overlay (cursor) within the scrollback; (0, 0) after [clearScreen]. */
    val cursorRow: Int get() = buffer.size
    val cursorColumn: Int get() = 0

    /** Glyph size in pixels; defaults to 14sp. Changing it recomputes the grid. */
    var textSizePx: Float
        get() = renderer.textSizePx
        set(value) {
            if (value <= 0f || value == renderer.textSizePx) return
            renderer.applyTextSize(value)
            recomputeGrid(width, height)
            invalidate()
        }

    @VisibleForTesting internal val rowBuffer: TerminalRowBuffer get() = buffer
    @VisibleForTesting internal var tapCount = 0
        private set
    /** Test seam: observes routed taps without needing to subclass the final controller. */
    @VisibleForTesting internal var onTapListener: (() -> Unit)? = null

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        isVerticalScrollBarEnabled = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = context.getString(R.string.commandroid_terminal_description)
        setBackgroundColor(renderer.backgroundColor)
        val padding = resources.getDimensionPixelSize(R.dimen.commandroid_terminal_padding)
        if (paddingLeft == 0 && paddingTop == 0 && paddingRight == 0 && paddingBottom == 0) {
            setPadding(padding, padding, padding, padding)
        }
    }

    /** Replaces the scrollback contents with [lines] (already filtered to the active session). */
    fun submitLines(lines: List<TerminalRenderedLine>) {
        val start = appendStartFor(lines)
        if (start < 0) {
            buffer.clear()
        }
        val from = if (start < 0) 0 else start
        for (index in from until lines.size) {
            val line = lines[index]
            buffer.appendLine(line.text, line.kind)
        }
        submittedCount = lines.size
        lastSubmitted = lines.lastOrNull()
        if (start >= 0 && from == lines.size) return // Nothing changed: no redraw.
        onContentChanged()
    }

    /** Updates the staged prompt overlay row: prompt prefix, staged text, active IME composing span. */
    fun setPromptState(prompt: String, stagedText: String, composingRange: IntRange?) {
        var start = -1
        var end = -1
        if (composingRange != null && !composingRange.isEmpty()) {
            start = TerminalRenderer.surrogateSafeIndex(stagedText, composingRange.first, preferStart = true)
            end = TerminalRenderer.surrogateSafeIndex(stagedText, composingRange.last + 1, preferStart = false)
            if (start >= end) {
                start = -1; end = -1
            }
        }
        if (prompt == this.prompt && stagedText == staged && start == composingStart && end == composingEnd) return
        this.prompt = prompt
        staged = stagedText
        composingStart = start
        composingEnd = end
        if (followBottom) scrollPx = maxScroll()
        invalidate()
    }

    /** Clears the scrollback ring buffer and resets the cursor to (0, 0). */
    fun clearScreen() {
        // Submitted-line bookkeeping is kept, so a later list that extends the old one only shows new lines.
        buffer.clear()
        evictedSeen = buffer.evictedRows
        scroller.forceFinished(true)
        scrollPx = 0
        followBottom = true
        invalidate()
    }

    /** Index in [lines] from which to append, or -1 when the buffer must be rebuilt. */
    private fun appendStartFor(lines: List<TerminalRenderedLine>): Int {
        val previous = lastSubmitted
        if (previous == null || submittedCount == 0) return if (buffer.size == 0) 0 else -1
        if (lines.size >= submittedCount && lines[submittedCount - 1] == previous) return submittedCount
        // Sliding window (producer trimmed its head): find the previously last instance by identity.
        for (index in lines.size - 1 downTo 0) {
            if (lines[index] === previous) return index + 1
        }
        return -1
    }

    private fun onContentChanged() {
        val evicted = buffer.evictedRows - evictedSeen
        evictedSeen = buffer.evictedRows
        if (followBottom) {
            scroller.forceFinished(true)
            scrollPx = maxScroll()
        } else {
            // Keep the rows the user is reading in place while older rows fall off the top.
            scrollPx = (scrollPx - evicted * renderer.lineHeight).coerceIn(0L, maxScroll().toLong()).toInt()
        }
        invalidate()
    }

    private fun contentHeight(): Int = (buffer.size + 1) * renderer.lineHeight

    private fun viewportHeight(): Int = maxOf(0, height - paddingTop - paddingBottom)

    private fun maxScroll(): Int = maxOf(0, contentHeight() - viewportHeight())

    private fun scrollToPx(target: Int) {
        val clamped = target.coerceIn(0, maxScroll())
        followBottom = clamped >= maxScroll()
        if (clamped == scrollPx) return
        scrollPx = clamped
        awakenScrollBars()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recomputeGrid(w, h)
    }

    private fun recomputeGrid(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val newColumns = renderer.columnsFor(w - paddingLeft - paddingRight)
        val newRows = renderer.rowsFor(h - paddingTop - paddingBottom)
        if (newColumns != buffer.columns) {
            buffer.resize(newColumns)
            evictedSeen = buffer.evictedRows
        }
        scrollPx = if (followBottom) maxScroll() else scrollPx.coerceIn(0, maxScroll())
        if (newColumns != notifiedColumns || newRows != notifiedRows) {
            notifiedColumns = newColumns
            notifiedRows = newRows
            onGridResized?.invoke(newColumns, newRows)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val lineHeight = renderer.lineHeight
        val left = paddingLeft.toFloat()
        val viewportTop = paddingTop
        val viewportBottom = height - paddingBottom
        canvas.save()
        canvas.clipRect(paddingLeft, viewportTop, width - paddingRight, viewportBottom)
        var rowIndex = scrollPx / lineHeight
        var top = viewportTop + rowIndex * lineHeight - scrollPx
        val total = buffer.size
        while (rowIndex < total && top < viewportBottom) {
            renderer.drawRow(canvas, buffer[rowIndex], left, top.toFloat())
            rowIndex++
            top += lineHeight
        }
        if (rowIndex == total && top < viewportBottom) {
            renderer.drawPromptRow(
                canvas, left, top.toFloat(), (width - paddingLeft - paddingRight).toFloat(),
                prompt, staged, composingStart, composingEnd, drawCursor = isFocused
            )
        }
        canvas.restore()
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollToPx(scroller.currY)
            if (!scroller.isFinished) postInvalidateOnAnimation()
        }
    }

    override fun computeVerticalScrollRange(): Int = contentHeight()
    override fun computeVerticalScrollOffset(): Int = scrollPx
    override fun computeVerticalScrollExtent(): Int = viewportHeight()

    // performClick() is invoked from GestureListener.onSingleTapUp, so taps stay accessible.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN && !scroller.isFinished) {
            scroller.forceFinished(true)
        }
        gestureDetector.onTouchEvent(event)
        return true
    }

    override fun performClick(): Boolean {
        val handled = super.performClick()
        routeTap()
        return handled
    }

    private fun routeTap() {
        tapCount++
        onTapListener?.invoke()
        inputController?.onTerminalTap()
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? =
        inputController?.onCreateInputConnection(outAttrs)

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        invalidate() // Cursor visibility follows focus.
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            // Only fires for taps that were not part of a scroll, fling or long press.
            performClick()
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            scrollToPx(scrollPx + distanceY.toInt())
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            val max = maxScroll()
            if (max == 0) return false
            scroller.fling(0, scrollPx, 0, -velocityY.toInt(), 0, 0, 0, max)
            postInvalidateOnAnimation()
            return true
        }
    }
}
