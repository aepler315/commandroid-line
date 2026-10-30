// SPDX-License-Identifier: GPL-3.0-only
package com.aepler.commandroid.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aepler.commandroid.data.process.ProcessSessionRepository
import com.aepler.commandroid.domain.BuiltInTerminalAction
import com.aepler.commandroid.domain.TranslateCommandUseCase
import com.aepler.commandroid.domain.TranslationResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * MVI host for the commandroid terminal. All state transitions run on the main dispatcher through
 * [TerminalReducer]; I/O is delegated to [ProcessSessionRepository].
 */
@HiltViewModel
class TerminalViewModel @Inject constructor(
    private val translateCommand: TranslateCommandUseCase,
    private val repository: ProcessSessionRepository,
    private val sessionEnvironment: SessionEnvironmentProvider
) : ViewModel() {

    private var model = TerminalModel()
    private val _state = MutableStateFlow(TerminalViewState())
    val state: StateFlow<TerminalViewState> = _state.asStateFlow()

    private val sideEffectChannel = Channel<TerminalSideEffect>(Channel.BUFFERED)
    val sideEffects: Flow<TerminalSideEffect> = sideEffectChannel.receiveAsFlow()

    init {
        reduce { TerminalReducer.createSession(it, sessionEnvironment.defaultWorkingDirectory()) }
        emitRestartIme()
        viewModelScope.launch {
            repository.output.collect { chunk -> reduce { TerminalReducer.appendOutput(it, chunk) } }
        }
        viewModelScope.launch {
            repository.completions.collect { completion -> reduce { TerminalReducer.completeCommand(it, completion) } }
        }
    }

    fun dispatch(intent: TerminalIntent) {
        when (intent) {
            is TerminalIntent.UpdateStagedInput ->
                reduce { TerminalReducer.updateStagedInput(it, intent.stagedText, intent.composingRange, intent.cursorIndex) }
            is TerminalIntent.SubmitCommand -> submit(intent.rawCommand, intent.sessionId)
            is TerminalIntent.SwitchSession -> switchTo(intent.sessionId)
            TerminalIntent.CreateSession -> {
                reduce { TerminalReducer.createSession(it, sessionEnvironment.defaultWorkingDirectory()) }
                emitRestartIme()
            }
            is TerminalIntent.KillActiveProcess -> kill(intent.sessionId)
            is TerminalIntent.ResizeTerminal -> reduce { TerminalReducer.resize(it, intent.columns, intent.rows) }
            TerminalIntent.ClearScreen -> reduce { TerminalReducer.clearScreen(it, it.activeSessionId) }
            is TerminalIntent.SendRawInput -> sendRaw(intent.sessionId, intent.bytes)
        }
    }

    /** Cycles to the next session (wrapping); no-op with a single session. */
    fun switchToNextSession() {
        TerminalReducer.nextSessionId(model)?.let(::switchTo)
    }

    private fun switchTo(sessionId: String) {
        if (sessionId == model.activeSessionId || model.session(sessionId) == null) return
        reduce { TerminalReducer.switchSession(it, sessionId) }
        emitRestartIme()
    }

    private fun submit(rawCommand: String, stagedSessionId: String? = null) {
        // A line staged in a session that is no longer active (closed by an earlier `exit` of the
        // same paste, or switched away from) must not run in whichever session is active now.
        if (stagedSessionId != null && stagedSessionId != model.activeSessionId) return
        val session = model.activeSession ?: return
        val sessionId = session.sessionId
        val line = rawCommand.trimEnd('\r', '\n')
        reduce {
            val echoed = TerminalReducer.echoCommand(it, sessionId, line, withPrompt = !session.isProcessRunning)
            TerminalReducer.clearStagedInput(echoed, sessionId)
        }

        if (session.isProcessRunning) {
            // A foreground job owns the shell; forward the line as its stdin.
            viewModelScope.launch {
                runCatching { repository.writeStdin(sessionId, (line + "\n").toByteArray(Charsets.UTF_8)) }
                    .onFailure { if (it is CancellationException) throw it else diagnostic(sessionId, "Could not write to the running process.") }
            }
            return
        }
        if (line.isBlank()) return

        val environment = sessionEnvironment.environment()
        val result = try {
            translateCommand(line, session.workingDirectory, environment)
        } catch (e: RuntimeException) {
            diagnostic(sessionId, "Command translation failed: ${e.javaClass.simpleName}")
            return
        }

        when (result) {
            is TranslationResult.Translated -> execute(sessionId, result, environment)
            is TranslationResult.BuiltInAction -> runBuiltIn(sessionId, result.action)
            is TranslationResult.UnsupportedCommandError -> {
                val messages = buildList {
                    add("'${result.offendingToken}' is not supported on Android: ${result.diagnosticExplanation}")
                    result.suggestedEquivalent?.let { add("Try instead: $it") }
                }
                reduce { TerminalReducer.appendDiagnostic(it, sessionId, messages) }
                sideEffectChannel.trySend(TerminalSideEffect.HapticFeedback(isError = true))
            }
        }
    }

    private fun execute(sessionId: String, result: TranslationResult.Translated, environment: Map<String, String>) {
        val session = model.session(sessionId) ?: return
        val banner = result.explanation?.let {
            TerminalReducer.bannerText(result.originalCommand, result.posixCommand, it)
        }
        val pending = PendingCommand(
            isDirectoryChange = TerminalReducer.isDirectoryChange(
                result.originalCommand, result.posixCommand, result.updatedWorkingDirectory
            ),
            submittedWorkingDirectory = session.workingDirectory,
            predictedWorkingDirectory = result.updatedWorkingDirectory
        )
        reduce { TerminalReducer.startCommand(TerminalReducer.showBanner(it, sessionId, banner), sessionId, pending) }
        viewModelScope.launch {
            try {
                repository.execute(sessionId, result.posixCommand, session.workingDirectory, environment)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reduce { TerminalReducer.markStopped(it, sessionId) }
                diagnostic(sessionId, "Could not start the shell: ${e.javaClass.simpleName}")
                sideEffectChannel.trySend(TerminalSideEffect.HapticFeedback(isError = true))
            }
        }
    }

    private fun runBuiltIn(sessionId: String, action: BuiltInTerminalAction) {
        when (action) {
            BuiltInTerminalAction.CLEAR_SCREEN -> reduce { TerminalReducer.clearScreen(it, sessionId) }
            BuiltInTerminalAction.PRINT_HELP -> reduce {
                TerminalReducer.appendLines(it, sessionId, HELP_LINES.map { text -> TerminalRenderedLine(sessionId, text) })
            }
            BuiltInTerminalAction.EXIT_SESSION -> {
                reduce { TerminalReducer.closeSession(it, sessionId, sessionEnvironment.defaultWorkingDirectory()) }
                viewModelScope.launch {
                    runCatching { repository.closeSession(sessionId) }
                        .onFailure { if (it is CancellationException) throw it }
                }
                emitRestartIme()
            }
        }
    }

    private fun kill(sessionId: String) {
        val session = model.session(sessionId) ?: return
        if (!session.isProcessRunning && !repository.isRunning(sessionId)) {
            // CMD at an idle prompt: Ctrl+C abandons the staged line. Never destroy the idle
            // persistent shell (its exported variables and other state would be lost).
            reduce {
                val echoed = TerminalReducer.echoCommand(it, sessionId, session.stagedInput + "^C")
                TerminalReducer.clearStagedInput(echoed, sessionId)
            }
            return
        }
        reduce { TerminalReducer.echoInterrupt(it, sessionId) }
        viewModelScope.launch {
            try {
                repository.kill(sessionId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                diagnostic(sessionId, "Could not stop the process: ${e.javaClass.simpleName}")
            }
            if (!repository.isRunning(sessionId)) reduce { TerminalReducer.markStopped(it, sessionId) }
        }
    }

    private fun sendRaw(sessionId: String, bytes: ByteArray) {
        val session = model.session(sessionId) ?: return
        if (bytes.isEmpty()) return
        if (!session.isProcessRunning) {
            // The idle shell reads commands from the same pipe; raw bytes would corrupt the next one.
            sideEffectChannel.trySend(TerminalSideEffect.HapticFeedback(isError = true))
            return
        }
        val copy = bytes.copyOf()
        viewModelScope.launch {
            runCatching { repository.writeStdin(sessionId, copy) }
                .onFailure { if (it is CancellationException) throw it else diagnostic(sessionId, "Could not write to the running process.") }
        }
    }

    private fun diagnostic(sessionId: String, message: String) {
        reduce { TerminalReducer.appendDiagnostic(it, sessionId, listOf(message)) }
    }

    private fun emitRestartIme() {
        val active = model.activeSession ?: return
        sideEffectChannel.trySend(TerminalSideEffect.RestartImeForSession(active.sessionId, active.stagedInput))
    }

    private inline fun reduce(transform: (TerminalModel) -> TerminalModel) {
        val updated = transform(model)
        if (updated === model) return
        model = updated
        _state.value = TerminalReducer.toViewState(updated)
    }

    companion object {
        val HELP_LINES: List<String> = listOf(
            "commandroid-line: Windows CMD commands are translated to the Android shell.",
            "  dir [/a] [/s] [/b]    list files            cd, chdir <path>   change directory",
            "  type <file>           print a file          copy, xcopy        copy files",
            "  move, ren             move or rename        del, erase, rmdir  remove files",
            "  findstr <pat> <file>  search text           where <binary>     locate a program",
            "  ipconfig [/all]       network interfaces    tasklist, taskkill processes",
            "  echo %VAR%            print variables       cls                clear the screen",
            "  exit                  close this session    help               show this help",
            "Paths: C:\\ maps to /storage/emulated/0; backslashes become '/'.",
            "Plain POSIX commands are passed through to /system/bin/sh. Ctrl+C stops the running command."
        )
    }
}
