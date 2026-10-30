// SPDX-License-Identifier: GPL-3.0-only
package com.aepler.commandroid.ui.neoterm

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import com.aepler.commandroid.presentation.TerminalLineKind
import com.aepler.commandroid.presentation.TerminalRenderedLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NeoTermViewTest {
    private val context get() = RuntimeEnvironment.getApplication()

    private fun laidOut(width: Int = 800, height: Int = 600): NeoTermView {
        val view = NeoTermView(context)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, width, height)
        return view
    }

    private fun line(text: String, kind: TerminalLineKind = TerminalLineKind.STDOUT) =
        TerminalRenderedLine("s1", text, kind)

    @Test fun isTextEditorAndFocusable() {
        val view = NeoTermView(context)
        assertTrue(view.onCheckIsTextEditor())
        assertTrue(view.isFocusable)
        assertTrue(view.isFocusableInTouchMode)
        assertNull(view.onCreateInputConnection(EditorInfo()))
        view.inputController = controllerFor(view)
        assertNotNull(view.onCreateInputConnection(EditorInfo()))
    }

    @Test fun defaultTextSizeIs14sp() {
        val view = NeoTermView(context)
        assertEquals(14f * context.resources.displayMetrics.scaledDensity, view.textSizePx, 0.01f)
    }

    @Test fun singleTapRoutesToController() {
        val view = laidOut()
        view.inputController = controllerFor(view)
        var taps = 0
        view.onTapListener = { taps++ }
        tap(view, 100f, 100f)
        assertEquals(1, taps)
        assertEquals(1, view.tapCount)
    }

    @Test fun dragIsNotATap() {
        val view = laidOut()
        val now = SystemClock.uptimeMillis()
        view.onTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 100f, 100f, 0))
        view.onTouchEvent(MotionEvent.obtain(now, now + 20, MotionEvent.ACTION_MOVE, 100f, 300f, 0))
        view.onTouchEvent(MotionEvent.obtain(now, now + 40, MotionEvent.ACTION_UP, 100f, 400f, 0))
        assertEquals(0, view.tapCount)
    }

    @Test fun accessibilityClickRoutesTap() {
        val view = laidOut()
        view.performClick()
        assertEquals(1, view.tapCount)
    }

    @Test fun gridResizeCallbackReportsColumnsAndRows() {
        val view = NeoTermView(context)
        view.setPadding(0, 0, 0, 0)
        val reported = mutableListOf<Pair<Int, Int>>()
        view.onGridResized = { c, r -> reported += c to r }
        view.measure(
            View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, 800, 600)
        val expectedColumns = (800 / view.charWidthPx).toInt()
        val expectedRows = 600 / view.lineHeightPx
        assertEquals(listOf(expectedColumns to expectedRows), reported)
        assertEquals(expectedColumns, view.columns)

        view.layout(0, 0, 400, 600)
        assertEquals(2, reported.size)
        assertEquals((400 / view.charWidthPx).toInt() to expectedRows, reported[1])
        assertEquals(reported[1].first, view.rowBuffer.columns)

        view.layout(0, 0, 400, 600) // Same size: no callback.
        assertEquals(2, reported.size)
    }

    @Test fun submitLinesAppendsOnlyNewLinesAndRebuildsOnDivergence() {
        val view = laidOut()
        val a = line("a"); val b = line("b", TerminalLineKind.STDERR); val c = line("c")
        view.submitLines(listOf(a, b))
        val firstRow = view.rowBuffer[0]
        view.submitLines(listOf(a, b, c))
        assertEquals(3, view.rowBuffer.size)
        assertTrue(firstRow === view.rowBuffer[0]) // Fast path did not rebuild.
        assertEquals(TerminalLineKind.STDERR, view.rowBuffer[1].kind)

        view.submitLines(listOf(b, c)) // Producer trimmed its head: nothing new to show.
        assertEquals(3, view.rowBuffer.size)

        view.submitLines(listOf(line("x"), line("y")))
        assertEquals(listOf("x", "y"), (0 until view.rowBuffer.size).map { view.rowBuffer[it].text() })
    }

    @Test fun clearScreenResetsBufferAndCursor() {
        val view = laidOut()
        val lines = (0 until 50).map { line("row $it") }
        view.submitLines(lines)
        assertEquals(50, view.cursorRow)
        view.clearScreen()
        assertEquals(0, view.rowBuffer.size)
        assertEquals(0, view.cursorRow)
        assertEquals(0, view.cursorColumn)
        view.submitLines(lines + line("after"))
        assertEquals(listOf("after"), (0 until view.rowBuffer.size).map { view.rowBuffer[it].text() })
    }

    @Test fun drawsHeavyStreamAndPromptWithoutCrashing() {
        val view = laidOut()
        val lines = ArrayList<TerminalRenderedLine>()
        repeat(6000) { lines += line("output $it ".repeat(20)) }
        view.submitLines(lines)
        assertEquals(TerminalRowBuffer.MAX_SCROLLBACK_LINES, view.rowBuffer.size)
        view.setPromptState("C:\\storage\\emulated\\0> ", "echo \uD83D\uDE80\uD83D\uDE80", 5..5)
        view.draw(Canvas(Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)))
    }

    @Test fun surrogateSafeIndexNeverSplitsPairs() {
        val text = "echo \uD83D\uDE80"
        assertEquals(5, TerminalRenderer.surrogateSafeIndex(text, 6, preferStart = true))
        assertEquals(7, TerminalRenderer.surrogateSafeIndex(text, 6, preferStart = false))
        assertEquals(7, TerminalRenderer.surrogateSafeIndex(text, 99, preferStart = false))
        assertEquals(0, TerminalRenderer.surrogateSafeIndex(text, -3, preferStart = true))
    }

    private fun tap(view: View, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        view.onTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0))
        view.onTouchEvent(MotionEvent.obtain(now, now + 30, MotionEvent.ACTION_UP, x, y, 0))
    }

    private fun controllerFor(view: View) = NeoTermInputController(
        inputViewProvider = { view },
        onStagedBufferChanged = { _, _ -> },
        onCommitLine = { },
        onControlKeyIntercepted = { _, _ -> false }
    )
}
