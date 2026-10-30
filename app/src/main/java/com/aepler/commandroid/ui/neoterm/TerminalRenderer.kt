// SPDX-License-Identifier: GPL-3.0-only
package com.aepler.commandroid.ui.neoterm

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import com.aepler.commandroid.presentation.TerminalLineKind
import com.termux.R
import kotlin.math.ceil

/**
 * Draws the terminal grid and the staged prompt overlay. All paints and metrics are created up
 * front; the draw methods only issue Canvas calls on preallocated data, so onDraw allocates nothing.
 */
class TerminalRenderer(context: Context) {
    private val resources = context.resources

    private val kindPaints: Array<Paint> = TerminalLineKind.values().map { kind ->
        monospacePaint(context.getColor(colorFor(kind)))
    }.toTypedArray()
    private val promptPaint = monospacePaint(context.getColor(R.color.commandroid_prompt_prefix))
    private val stagedPaint = monospacePaint(context.getColor(R.color.commandroid_prompt_staged))
    private val composingPaint = monospacePaint(context.getColor(R.color.commandroid_prompt_composing))
    private val underlinePaint = Paint().apply {
        color = context.getColor(R.color.commandroid_prompt_composing)
        style = Paint.Style.FILL
    }
    private val cursorPaint = Paint().apply {
        color = context.getColor(R.color.commandroid_prompt_cursor)
        style = Paint.Style.FILL
    }
    private val allTextPaints: List<Paint> = kindPaints.toList() + listOf(promptPaint, stagedPaint, composingPaint)

    /** Underline thickness under the composing span: exactly 2px per spec. */
    val underlineThicknessPx: Float = resources.getDimension(R.dimen.commandroid_composing_underline)

    val backgroundColor: Int = context.getColor(R.color.commandroid_terminal_background)

    var textSizePx: Float = resources.getDimension(R.dimen.commandroid_terminal_text_size)
        private set

    /** Width of one monospace glyph cell in pixels. */
    var charWidth: Float = 1f
        private set

    /** Height of one grid row in pixels. */
    var lineHeight: Int = 1
        private set

    /** Distance from a row's top to its text baseline. */
    var baselineOffset: Float = 0f
        private set

    private var descent: Float = 0f

    init {
        applyTextSize(textSizePx)
    }

    /** Updates the glyph size; callers must recompute the grid afterwards. */
    fun applyTextSize(px: Float) {
        textSizePx = px
        for (paint in allTextPaints) paint.textSize = px
        val reference = kindPaints[0]
        val measured = reference.measureText("M")
        charWidth = if (measured > 0f) measured else px * FALLBACK_CHAR_WIDTH_RATIO
        val metrics = reference.fontMetrics
        val metricsHeight = metrics.descent - metrics.ascent
        if (metricsHeight > 0f) {
            lineHeight = maxOf(1, ceil(metricsHeight + metrics.leading).toInt())
            baselineOffset = -metrics.ascent
            descent = metrics.descent
        } else {
            lineHeight = maxOf(1, ceil(px * FALLBACK_LINE_HEIGHT_RATIO).toInt())
            baselineOffset = px
            descent = lineHeight - px
        }
    }

    fun columnsFor(widthPx: Int): Int = maxOf(1, (widthPx / charWidth).toInt())

    fun rowsFor(heightPx: Int): Int = maxOf(1, heightPx / lineHeight)

    fun drawRow(canvas: Canvas, row: TerminalRow, left: Float, top: Float) {
        if (row.length == 0) return
        canvas.drawText(row.chars, 0, row.length, left, top + baselineOffset, kindPaints[row.kind.ordinal])
    }

    /**
     * Draws `prompt + staged` on one grid row. [composingStart, composingEnd) must already be
     * surrogate-safe UTF-16 indices into [staged] (or -1 for no span). The cursor is drawn before
     * the code point at [cursorIndex] (a surrogate-safe index; defaults to the end). When the cursor
     * would fall outside [availableWidth] the line is shifted left so it stays visible.
     */
    fun drawPromptRow(
        canvas: Canvas,
        left: Float,
        top: Float,
        availableWidth: Float,
        prompt: String,
        staged: String,
        composingStart: Int,
        composingEnd: Int,
        drawCursor: Boolean,
        cursorIndex: Int = staged.length
    ) {
        val cursor = cursorIndex.coerceIn(0, staged.length)
        val promptWidth = promptPaint.measureText(prompt, 0, prompt.length)
        val stagedWidth = stagedPaint.measureText(staged, 0, staged.length)
        // Monospace: every text paint shares the glyph advance, so one measurement places the cursor.
        val cursorOffset = promptWidth + stagedPaint.measureText(staged, 0, cursor)
        val needed = cursorOffset + charWidth
        val origin = left + if (needed > availableWidth) availableWidth - needed else 0f
        var x = origin
        val baseline = top + baselineOffset
        if (prompt.isNotEmpty()) canvas.drawText(prompt, 0, prompt.length, x, baseline, promptPaint)
        x += promptWidth
        val hasSpan = composingStart in 0 until composingEnd && composingEnd <= staged.length
        if (!hasSpan) {
            if (staged.isNotEmpty()) canvas.drawText(staged, 0, staged.length, x, baseline, stagedPaint)
            x += stagedWidth
        } else {
            x = drawSegment(canvas, staged, 0, composingStart, x, baseline, stagedPaint)
            val spanStart = x
            x = drawSegment(canvas, staged, composingStart, composingEnd, x, baseline, composingPaint)
            val underlineTop = baseline + maxOf(1f, descent / 2f)
            canvas.drawRect(spanStart, underlineTop, x, underlineTop + underlineThicknessPx, underlinePaint)
            x = drawSegment(canvas, staged, composingEnd, staged.length, x, baseline, stagedPaint)
        }
        if (drawCursor) {
            val cursorX = origin + cursorOffset
            canvas.drawRect(cursorX, top, cursorX + maxOf(2f, charWidth / 4f), top + lineHeight, cursorPaint)
        }
    }

    private fun drawSegment(
        canvas: Canvas, text: String, start: Int, end: Int, x: Float, baseline: Float, paint: Paint
    ): Float {
        if (start >= end) return x
        canvas.drawText(text, start, end, x, baseline, paint)
        return x + paint.measureText(text, start, end)
    }

    private fun monospacePaint(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        this.color = color
        textSize = textSizePx
    }

    companion object {
        private const val FALLBACK_CHAR_WIDTH_RATIO = 0.6f
        private const val FALLBACK_LINE_HEIGHT_RATIO = 1.2f

        fun colorFor(kind: TerminalLineKind): Int = when (kind) {
            TerminalLineKind.STDOUT -> R.color.commandroid_terminal_stdout
            TerminalLineKind.STDERR -> R.color.commandroid_terminal_stderr
            TerminalLineKind.COMMAND_ECHO -> R.color.commandroid_terminal_command_echo
            TerminalLineKind.TRANSLATION_BANNER -> R.color.commandroid_terminal_banner
            TerminalLineKind.DIAGNOSTIC -> R.color.commandroid_terminal_diagnostic
        }

        /** Moves [rawIndex] (clamped to `[0, text.length]`) off the middle of a surrogate pair. */
        fun surrogateSafeIndex(text: CharSequence, rawIndex: Int, preferStart: Boolean): Int {
            val index = rawIndex.coerceIn(0, text.length)
            if (index in 1 until text.length && text[index - 1].isHighSurrogate() && text[index].isLowSurrogate()) {
                return if (preferStart) index - 1 else index + 1
            }
            return index
        }
    }
}
