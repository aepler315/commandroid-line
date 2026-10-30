// SPDX-License-Identifier: GPL-3.0-only
package com.aepler.commandroid.presentation

import java.io.File

sealed interface TerminalIntent {
    data class UpdateStagedInput(val stagedText: String, val composingRange: IntRange?) : TerminalIntent
    data class SubmitCommand(val rawCommand: String) : TerminalIntent
    data class SwitchSession(val sessionId: String) : TerminalIntent
    data object CreateSession : TerminalIntent
    data class KillActiveProcess(val sessionId: String) : TerminalIntent
    data class ResizeTerminal(val columns: Int, val rows: Int) : TerminalIntent
    data object ClearScreen : TerminalIntent
}

data class TerminalSessionState(
    val sessionId: String,
    val title: String,
    val workingDirectory: File,
    val windowsPromptDisplay: String, // e.g., "C:\\Users\\shell>"
    val stagedInput: String = "",
    val composingRange: IntRange? = null,
    val isProcessRunning: Boolean = false
)

enum class TerminalLineKind { STDOUT, STDERR, COMMAND_ECHO, TRANSLATION_BANNER, DIAGNOSTIC }

/** One immutable rendered row of a session's scrollback. */
data class TerminalRenderedLine(
    val sessionId: String,
    val text: String,
    val kind: TerminalLineKind = TerminalLineKind.STDOUT
)

data class TerminalViewState(
    val sessions: List<TerminalSessionState> = emptyList(),
    val activeSessionId: String = "",
    val terminalLines: List<TerminalRenderedLine> = emptyList(),
    val lastTranslationBanner: String? = null // Shows e.g. "[CMD -> SH] dir /a -> ls -la"
)

sealed interface TerminalSideEffect {
    data class RestartImeForSession(val sessionId: String, val restoredStagedText: String) : TerminalSideEffect
    data class HapticFeedback(val isError: Boolean) : TerminalSideEffect
}
