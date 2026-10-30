// SPDX-License-Identifier: GPL-3.0-only
package com.aepler.commandroid.ui.neoterm

import com.aepler.commandroid.presentation.TerminalLineKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalRowBufferTest {
    @Test fun defaultCapacityIs5000AndOldestRowsAreEvicted() {
        val buffer = TerminalRowBuffer(columns = 20)
        assertEquals(5000, buffer.maxScrollbackLines)
        for (i in 0 until 5003) buffer.appendLine("line $i")
        assertEquals(5000, buffer.size)
        assertEquals(3L, buffer.evictedRows)
        assertEquals("line 3", buffer[0].text())
        assertEquals("line 5002", buffer[4999].text())
    }

    @Test fun fullRingRecyclesRowObjects() {
        val buffer = TerminalRowBuffer(columns = 10, maxScrollbackLines = 3)
        repeat(3) { buffer.appendLine("x$it") }
        val oldest = buffer[0]
        buffer.appendLine("new")
        assertSame(oldest, buffer[2])
        assertEquals(listOf("x1", "x2", "new"), (0 until 3).map { buffer[it].text() })
    }

    @Test fun longLinesWrapAtColumnWidth() {
        val buffer = TerminalRowBuffer(columns = 4)
        assertEquals(3, buffer.appendLine("abcdefghij", TerminalLineKind.STDERR))
        assertEquals(listOf("abcd", "efgh", "ij"), (0 until 3).map { buffer[it].text() })
        assertTrue(buffer[0].wrapped)
        assertTrue(buffer[1].wrapped)
        assertFalse(buffer[2].wrapped)
        assertTrue((0 until 3).all { buffer[it].kind == TerminalLineKind.STDERR })
    }

    @Test fun resizeRewrapsLogicalLines() {
        val buffer = TerminalRowBuffer(columns = 4)
        buffer.appendLine("abcdefghij")
        buffer.appendLine("xy", TerminalLineKind.DIAGNOSTIC)
        buffer.resize(5)
        assertEquals(5, buffer.columns)
        assertEquals(listOf("abcde", "fghij", "xy"), (0 until buffer.size).map { buffer[it].text() })
        assertEquals(TerminalLineKind.DIAGNOSTIC, buffer[2].kind)
        buffer.resize(20)
        assertEquals(listOf("abcdefghij", "xy"), (0 until buffer.size).map { buffer[it].text() })
        assertTrue(buffer[0].chars.size >= 40)
    }

    @Test fun surrogatePairsAndWideCharsAreNeverSplit() {
        val buffer = TerminalRowBuffer(columns = 3)
        buffer.appendLine("ab🚀c")
        // The rocket is two cells wide and cannot fit after "ab", so it wraps whole.
        assertEquals("ab", buffer[0].text())
        assertEquals("🚀c", buffer[1].text())
        assertEquals(3, buffer[1].cells)
    }

    @Test fun embeddedNewlinesStartNewRowsAndControlsAreDropped() {
        val buffer = TerminalRowBuffer(columns = 10)
        assertEquals(2, buffer.appendLine("one\r\ntwo"))
        assertEquals("one", buffer[0].text())
        assertEquals("two", buffer[1].text())
        assertFalse(buffer[0].wrapped)
    }

    @Test fun clearResetsToEmpty() {
        val buffer = TerminalRowBuffer(columns = 10)
        repeat(10) { buffer.appendLine("row $it") }
        buffer.clear()
        assertEquals(0, buffer.size)
        buffer.appendLine("fresh")
        assertEquals(1, buffer.size)
        assertEquals("fresh", buffer[0].text())
    }
}
