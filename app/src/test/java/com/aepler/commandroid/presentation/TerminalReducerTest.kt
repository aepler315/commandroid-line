// SPDX-License-Identifier: GPL-3.0-only
package com.aepler.commandroid.presentation

import com.aepler.commandroid.data.process.TerminalOutputChunk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TerminalReducerTest {

    private val root = File("/storage/emulated/0")

    @Test
    fun promptMapsSharedStorageToDriveC() {
        assertEquals("C:\\>", WindowsPromptFormatter.format(root))
        assertEquals("C:\\>", WindowsPromptFormatter.format(File("/storage/emulated/0/")))
        assertEquals("C:\\Download>", WindowsPromptFormatter.format(File("/storage/emulated/0/Download")))
        assertEquals("C:\\Download\\a b>", WindowsPromptFormatter.format(File("/sdcard/Download/a b")))
        assertEquals("C:\\DCIM>", WindowsPromptFormatter.format(File("/storage/emulated/0/Download/../DCIM")))
    }

    @Test
    fun promptRendersOtherPathsWithBackslashes() {
        assertEquals(
            "C:\\data\\user\\0\\com.neoterm\\files>",
            WindowsPromptFormatter.format(File("/data/user/0/com.neoterm/files"))
        )
    }

    @Test
    fun sessionsGetSequentialIds() {
        val model = TerminalReducer.createSession(TerminalReducer.createSession(TerminalModel(), root), root)
        assertEquals(listOf("session-1", "session-2"), model.sessions.map { it.sessionId })
        assertEquals(listOf("Session 1", "Session 2"), model.sessions.map { it.title })
        assertEquals("session-2", model.activeSessionId)
    }

    @Test
    fun closingMiddleSessionKeepsActiveAndDropsItsLines() {
        var model = TerminalModel()
        repeat(3) { model = TerminalReducer.createSession(model, root) }
        model = TerminalReducer.appendText(model, "session-2", "x", TerminalLineKind.STDOUT)
        model = TerminalReducer.closeSession(model, "session-2", root)
        assertEquals(listOf("session-1", "session-3"), model.sessions.map { it.sessionId })
        assertEquals("session-3", model.activeSessionId)
        assertFalse(model.linesBySession.containsKey("session-2"))
    }

    @Test
    fun unknownSessionOperationsAreNoOps() {
        val model = TerminalReducer.createSession(TerminalModel(), root)
        assertSame(model, TerminalReducer.switchSession(model, "nope"))
        assertSame(model, TerminalReducer.closeSession(model, "nope", root))
        assertSame(model, TerminalReducer.appendOutput(model, TerminalOutputChunk("nope", "x\n", false)))
    }

    @Test
    fun scrollbackCapKeepsNewestLines() {
        var model = TerminalReducer.createSession(TerminalModel(), root)
        model = TerminalReducer.appendOutput(model, TerminalOutputChunk("session-1", "a\nb\nc\nd\n", false), maxLines = 3)
        assertEquals(listOf("b", "c", "d"), TerminalReducer.toViewState(model).terminalLines.map { it.text })
    }

    @Test
    fun directoryChangeDetection() {
        assertTrue(TerminalReducer.isDirectoryChange("cd ..", "cd .. && pwd", null))
        assertTrue(TerminalReducer.isDirectoryChange("CD\\", "cd /storage/emulated/0 && pwd", null))
        assertTrue(TerminalReducer.isDirectoryChange("chdir /d D:\\x", "cd /x", null))
        assertTrue(TerminalReducer.isDirectoryChange("x", "y", File("/tmp")))
        assertFalse(TerminalReducer.isDirectoryChange("dir", "ls -l", null))
        assertFalse(TerminalReducer.isDirectoryChange("cdrom", "cdrom", null))
    }
}
