// SPDX-License-Identifier: GPL-3.0-only
package com.aepler.commandroid.presentation

import com.aepler.commandroid.data.process.CommandCompletion
import com.aepler.commandroid.data.process.ProcessSessionRepository
import com.aepler.commandroid.data.process.TerminalOutputChunk
import com.aepler.commandroid.domain.BuiltInTerminalAction
import com.aepler.commandroid.domain.TranslateCommandUseCase
import com.aepler.commandroid.domain.TranslationResult
import com.aepler.commandroid.domain.WindowsToLinuxTranslator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val home = File("/storage/emulated/0")
    private lateinit var translator: FakeTranslator
    private lateinit var repository: FakeRepository
    private lateinit var viewModel: TerminalViewModel
    private val sideEffects = mutableListOf<TerminalSideEffect>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        translator = FakeTranslator()
        repository = FakeRepository()
        viewModel = TerminalViewModel(TranslateCommandUseCase(translator), repository, FakeEnvironment(home))
        // Let the ViewModel subscribe to the repository flows before tests emit into them.
        dispatcher.scheduler.advanceUntilIdle()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.collectSideEffects() {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.sideEffects.collect { sideEffects += it } }
    }

    private val state get() = viewModel.state.value
    private val active get() = state.activeSession!!

    @Test
    fun initialSessionUsesDefaultDirectoryAndRestartsIme() = runTest(dispatcher) {
        collectSideEffects()
        advanceUntilIdle()
        assertEquals("session-1", state.activeSessionId)
        assertEquals(home, active.workingDirectory)
        assertEquals("C:\\>", active.windowsPromptDisplay)
        assertEquals(listOf(TerminalSideEffect.RestartImeForSession("session-1", "")), sideEffects)
    }

    @Test
    fun stagedInputIsKeptPerSessionAndRestoredOnSwitch() = runTest(dispatcher) {
        collectSideEffects()
        viewModel.dispatch(TerminalIntent.UpdateStagedInput("dir", 0..2))
        viewModel.dispatch(TerminalIntent.CreateSession)
        assertEquals("session-2", state.activeSessionId)
        assertEquals("", active.stagedInput)
        viewModel.dispatch(TerminalIntent.UpdateStagedInput("cls", null))

        viewModel.dispatch(TerminalIntent.SwitchSession("session-1"))
        advanceUntilIdle()

        assertEquals("dir", active.stagedInput)
        assertEquals(0..2, active.composingRange)
        assertEquals("cls", state.sessions.first { it.sessionId == "session-2" }.stagedInput)
        assertEquals(
            listOf(
                TerminalSideEffect.RestartImeForSession("session-1", ""),
                TerminalSideEffect.RestartImeForSession("session-2", ""),
                TerminalSideEffect.RestartImeForSession("session-1", "dir")
            ),
            sideEffects
        )
    }

    @Test
    fun switchingToActiveOrUnknownSessionEmitsNothing() = runTest(dispatcher) {
        collectSideEffects()
        advanceUntilIdle()
        sideEffects.clear()
        viewModel.dispatch(TerminalIntent.SwitchSession("session-1"))
        viewModel.dispatch(TerminalIntent.SwitchSession("session-9"))
        advanceUntilIdle()
        assertTrue(sideEffects.isEmpty())
        assertEquals("session-1", state.activeSessionId)
    }

    @Test
    fun submitEchoesShowsBannerAndExecutesPosixCommand() = runTest(dispatcher) {
        translator.result = { raw, _ -> TranslationResult.Translated(raw, "ls -la", "dir /a -> ls -la") }
        viewModel.dispatch(TerminalIntent.UpdateStagedInput("dir /a", null))
        viewModel.dispatch(TerminalIntent.SubmitCommand("dir /a"))
        advanceUntilIdle()

        assertEquals(
            listOf(
                TerminalRenderedLine("session-1", "C:\\> dir /a", TerminalLineKind.COMMAND_ECHO),
                TerminalRenderedLine("session-1", "[CMD -> SH] dir /a -> ls -la  # dir /a -> ls -la", TerminalLineKind.TRANSLATION_BANNER)
            ),
            state.terminalLines
        )
        assertEquals("[CMD -> SH] dir /a -> ls -la  # dir /a -> ls -la", state.lastTranslationBanner)
        assertEquals(listOf(Execution("session-1", "ls -la", home)), repository.executions)
        assertTrue(active.isProcessRunning)
        assertEquals("", active.stagedInput)

        repository.completions.emit(CommandCompletion("session-1", 0, home))
        advanceUntilIdle()
        assertFalse(active.isProcessRunning)
    }

    @Test
    fun bannerAlreadyInCanonicalFormIsUsedVerbatim() {
        assertEquals(
            "[CMD -> SH] dir /a -> ls -la",
            TerminalReducer.bannerText("dir /a", "ls -la", "[CMD -> SH] dir /a -> ls -la")
        )
    }

    @Test
    fun passThroughCommandHasNoBanner() = runTest(dispatcher) {
        viewModel.dispatch(TerminalIntent.SubmitCommand("ls"))
        advanceUntilIdle()
        assertEquals(listOf(TerminalLineKind.COMMAND_ECHO), state.terminalLines.map { it.kind })
        assertNull(state.lastTranslationBanner)
        assertEquals("ls", repository.executions.single().command)
    }

    @Test
    fun cdUpdatesWorkingDirectoryOnlyOnExitZero() = runTest(dispatcher) {
        val download = File("/storage/emulated/0/Download")
        translator.result = { raw, _ -> TranslationResult.Translated(raw, "cd /storage/emulated/0/Download && pwd", null) }

        viewModel.dispatch(TerminalIntent.SubmitCommand("cd Download"))
        advanceUntilIdle()
        repository.completions.emit(CommandCompletion("session-1", 1, home))
        advanceUntilIdle()
        assertEquals(home, active.workingDirectory)
        assertEquals("C:\\>", active.windowsPromptDisplay)

        viewModel.dispatch(TerminalIntent.SubmitCommand("cd Download"))
        advanceUntilIdle()
        assertEquals(home, repository.executions.last().workingDir)
        repository.completions.emit(CommandCompletion("session-1", 0, download))
        advanceUntilIdle()
        assertEquals(download, active.workingDirectory)
        assertEquals("C:\\Download>", active.windowsPromptDisplay)
    }

    @Test
    fun cdFallsBackToTranslatorPredictionWhenShellReportsOldDirectory() = runTest(dispatcher) {
        val target = File("/storage/emulated/0/DCIM")
        translator.result = { raw, _ -> TranslationResult.Translated(raw, "cd /storage/emulated/0/DCIM && pwd", null, target) }
        viewModel.dispatch(TerminalIntent.SubmitCommand("cd DCIM"))
        advanceUntilIdle()
        repository.completions.emit(CommandCompletion("session-1", 0, home))
        advanceUntilIdle()
        assertEquals(target, active.workingDirectory)
    }

    @Test
    fun nonCdCommandDoesNotChangeDirectory() = runTest(dispatcher) {
        viewModel.dispatch(TerminalIntent.SubmitCommand("ls"))
        advanceUntilIdle()
        repository.completions.emit(CommandCompletion("session-1", 0, File("/data")))
        advanceUntilIdle()
        assertEquals(home, active.workingDirectory)
    }

    @Test
    fun clsClearsOnlyThatSession() = runTest(dispatcher) {
        repository.output.emit(TerminalOutputChunk("session-1", "one\n", false))
        viewModel.dispatch(TerminalIntent.CreateSession)
        repository.output.emit(TerminalOutputChunk("session-2", "two\n", false))
        advanceUntilIdle()
        translator.result = { _, _ -> TranslationResult.BuiltInAction(BuiltInTerminalAction.CLEAR_SCREEN) }

        viewModel.dispatch(TerminalIntent.SubmitCommand("cls"))
        advanceUntilIdle()
        assertTrue(state.terminalLines.isEmpty())
        assertTrue(repository.executions.isEmpty())

        viewModel.dispatch(TerminalIntent.SwitchSession("session-1"))
        assertEquals(listOf("one"), state.terminalLines.map { it.text })
    }

    @Test
    fun exitClosesSessionAndCreatesReplacementWhenLast() = runTest(dispatcher) {
        collectSideEffects()
        translator.result = { _, _ -> TranslationResult.BuiltInAction(BuiltInTerminalAction.EXIT_SESSION) }
        viewModel.dispatch(TerminalIntent.SubmitCommand("exit"))
        advanceUntilIdle()
        assertEquals(listOf("session-2"), state.sessions.map { it.sessionId })
        assertEquals("session-2", state.activeSessionId)
        assertEquals(listOf("session-1"), repository.closed)
        assertEquals(TerminalSideEffect.RestartImeForSession("session-2", ""), sideEffects.last())
    }

    @Test
    fun exitSwitchesToRemainingSession() = runTest(dispatcher) {
        viewModel.dispatch(TerminalIntent.UpdateStagedInput("pwd", null))
        viewModel.dispatch(TerminalIntent.CreateSession)
        translator.result = { _, _ -> TranslationResult.BuiltInAction(BuiltInTerminalAction.EXIT_SESSION) }
        viewModel.dispatch(TerminalIntent.SubmitCommand("exit"))
        advanceUntilIdle()
        assertEquals(listOf("session-1"), state.sessions.map { it.sessionId })
        assertEquals("pwd", active.stagedInput)
    }

    @Test
    fun helpPrintsHelpLines() = runTest(dispatcher) {
        translator.result = { _, _ -> TranslationResult.BuiltInAction(BuiltInTerminalAction.PRINT_HELP) }
        viewModel.dispatch(TerminalIntent.SubmitCommand("help"))
        advanceUntilIdle()
        assertEquals(TerminalViewModel.HELP_LINES, state.terminalLines.drop(1).map { it.text })
    }

    @Test
    fun unsupportedCommandShowsDiagnosticAndErrorHaptic() = runTest(dispatcher) {
        collectSideEffects()
        translator.result = { _, _ ->
            TranslationResult.UnsupportedCommandError("regedit", "the Windows registry does not exist", "settings")
        }
        viewModel.dispatch(TerminalIntent.SubmitCommand("regedit"))
        advanceUntilIdle()
        val diagnostics = state.terminalLines.filter { it.kind == TerminalLineKind.DIAGNOSTIC }.map { it.text }
        assertEquals(
            listOf("'regedit' is not supported on Android: the Windows registry does not exist", "Try instead: settings"),
            diagnostics
        )
        assertTrue(repository.executions.isEmpty())
        assertEquals(TerminalSideEffect.HapticFeedback(isError = true), sideEffects.last())
    }

    @Test
    fun outputSplitAcrossChunksFormsLines() = runTest(dispatcher) {
        repository.output.emit(TerminalOutputChunk("session-1", "hel", false))
        advanceUntilIdle()
        assertEquals(listOf("hel"), state.terminalLines.map { it.text })
        repository.output.emit(TerminalOutputChunk("session-1", "lo\r\nwor", false))
        repository.output.emit(TerminalOutputChunk("session-1", "oops\n", true))
        repository.output.emit(TerminalOutputChunk("session-1", "ld\n", false))
        advanceUntilIdle()
        assertEquals(
            listOf(
                TerminalRenderedLine("session-1", "hello", TerminalLineKind.STDOUT),
                TerminalRenderedLine("session-1", "oops", TerminalLineKind.STDERR),
                TerminalRenderedLine("session-1", "world", TerminalLineKind.STDOUT)
            ),
            state.terminalLines
        )
    }

    @Test
    fun partialLineIsFlushedOnCompletion() = runTest(dispatcher) {
        viewModel.dispatch(TerminalIntent.SubmitCommand("ls"))
        repository.output.emit(TerminalOutputChunk("session-1", "no newline", false))
        advanceUntilIdle()
        repository.completions.emit(CommandCompletion("session-1", 0, home))
        advanceUntilIdle()
        repository.output.emit(TerminalOutputChunk("session-1", "next\n", false))
        advanceUntilIdle()
        assertEquals(listOf("C:\\> ls", "no newline", "next"), state.terminalLines.map { it.text })
    }

    @Test
    fun outputIsRoutedPerSessionAndOnlyActiveIsShown() = runTest(dispatcher) {
        viewModel.dispatch(TerminalIntent.CreateSession)
        repository.output.emit(TerminalOutputChunk("session-1", "a\n", false))
        repository.output.emit(TerminalOutputChunk("session-2", "b\n", false))
        repository.output.emit(TerminalOutputChunk("ghost", "c\n", false))
        advanceUntilIdle()
        assertEquals(listOf("b"), state.terminalLines.map { it.text })
        assertTrue(state.terminalLines.all { it.sessionId == "session-2" })
    }

    @Test
    fun scrollbackIsCappedPerSession() = runTest(dispatcher) {
        val text = (1..TerminalReducer.MAX_SCROLLBACK_LINES + 250).joinToString("") { "line $it\n" }
        repository.output.emit(TerminalOutputChunk("session-1", text, false))
        repository.output.emit(TerminalOutputChunk("session-1", "tail\n", false))
        advanceUntilIdle()
        assertEquals(TerminalReducer.MAX_SCROLLBACK_LINES, state.terminalLines.size)
        assertEquals("line 252", state.terminalLines.first().text)
        assertEquals("tail", state.terminalLines.last().text)
    }

    @Test
    fun killActiveProcessDelegatesToRepository() = runTest(dispatcher) {
        viewModel.dispatch(TerminalIntent.SubmitCommand("sleep 100"))
        advanceUntilIdle()
        assertTrue(active.isProcessRunning)
        viewModel.dispatch(TerminalIntent.KillActiveProcess("session-1"))
        advanceUntilIdle()
        assertEquals(listOf("session-1"), repository.killed)
        assertFalse(active.isProcessRunning)
    }

    @Test
    fun inputWhileProcessRunsGoesToStdin() = runTest(dispatcher) {
        viewModel.dispatch(TerminalIntent.SubmitCommand("cat"))
        advanceUntilIdle()
        repository.running += "session-1"
        viewModel.dispatch(TerminalIntent.SubmitCommand("hello"))
        advanceUntilIdle()
        assertEquals(1, repository.executions.size)
        assertEquals("hello\n", repository.stdin.single())
        assertEquals(1, translator.calls)
        assertEquals("hello", state.terminalLines.last().text)
    }

    @Test
    fun resizeIsStored() = runTest(dispatcher) {
        viewModel.dispatch(TerminalIntent.ResizeTerminal(120, 40))
        assertEquals(120, state.columns)
        assertEquals(40, state.rows)
        viewModel.dispatch(TerminalIntent.ResizeTerminal(0, 40))
        assertEquals(120, state.columns)
    }

    @Test
    fun clearScreenIntentClearsActiveSession() = runTest(dispatcher) {
        repository.output.emit(TerminalOutputChunk("session-1", "x\ny", false))
        advanceUntilIdle()
        viewModel.dispatch(TerminalIntent.ClearScreen)
        assertTrue(state.terminalLines.isEmpty())
    }

    @Test
    fun blankSubmitEchoesPromptWithoutTranslating() = runTest(dispatcher) {
        viewModel.dispatch(TerminalIntent.SubmitCommand("   "))
        advanceUntilIdle()
        assertEquals(0, translator.calls)
        assertTrue(repository.executions.isEmpty())
        assertEquals(TerminalLineKind.COMMAND_ECHO, state.terminalLines.single().kind)
    }

    @Test
    fun nextSessionWraps() = runTest(dispatcher) {
        viewModel.dispatch(TerminalIntent.CreateSession)
        viewModel.dispatch(TerminalIntent.CreateSession)
        viewModel.switchToNextSession()
        assertEquals("session-1", state.activeSessionId)
        viewModel.switchToNextSession()
        assertEquals("session-2", state.activeSessionId)
    }

    private class FakeEnvironment(private val dir: File) : SessionEnvironmentProvider {
        override fun defaultWorkingDirectory(): File = dir
        override fun environment(): Map<String, String> = mapOf("HOME" to "/home")
    }

    private class FakeTranslator : WindowsToLinuxTranslator {
        var calls = 0
        var result: (String, File) -> TranslationResult = { raw, _ -> TranslationResult.Translated(raw, raw, null) }
        override fun translate(rawCommand: String, currentDirectory: File, environment: Map<String, String>): TranslationResult {
            calls++
            return result(rawCommand, currentDirectory)
        }
    }

    data class Execution(val sessionId: String, val command: String, val workingDir: File)

    private class FakeRepository : ProcessSessionRepository {
        override val output = MutableSharedFlow<TerminalOutputChunk>(extraBufferCapacity = 64)
        override val completions = MutableSharedFlow<CommandCompletion>(extraBufferCapacity = 64)
        val executions = mutableListOf<Execution>()
        val stdin = mutableListOf<String>()
        val killed = mutableListOf<String>()
        val closed = mutableListOf<String>()
        val running = mutableSetOf<String>()

        override suspend fun execute(sessionId: String, posixCommand: String, workingDir: File, environment: Map<String, String>) {
            executions += Execution(sessionId, posixCommand, workingDir)
        }

        override suspend fun writeStdin(sessionId: String, inputBytes: ByteArray) {
            stdin += String(inputBytes, Charsets.UTF_8)
        }

        override suspend fun kill(sessionId: String): Boolean {
            killed += sessionId
            running -= sessionId
            return true
        }

        override suspend fun closeSession(sessionId: String) {
            closed += sessionId
        }

        override fun isRunning(sessionId: String): Boolean = sessionId in running
    }
}
