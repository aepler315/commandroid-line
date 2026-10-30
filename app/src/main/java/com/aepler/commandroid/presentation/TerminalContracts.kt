// SPDX-License-Identifier: GPL-3.0-only
package com.aepler.commandroid.presentation

import java.io.File

sealed interface TerminalIntent {
    /** [cursorIndex]: staged cursor (UTF-16 index); null means the end of [stagedText]. */
    data class UpdateStagedInput(
        val stagedText: String,
        val composingRange: IntRange?,
        val cursorIndex: Int? = null
    ) : TerminalIntent

    /**
     * [sessionId]: session the line was staged in. When set and no longer the active session (for
     * example an earlier `exit` line of the same paste closed it) the line is dropped.
     */
    data class SubmitCommand(val rawCommand: String, val sessionId: String? = null) : TerminalIntent
    data class SwitchSession(val sessionId: String) : TerminalIntent
    data object CreateSession : TerminalIntent
    data class KillActiveProcess(val sessionId: String) : TerminalIntent
    data class ResizeTerminal(val columns: Int, val rows: Int) : TerminalIntent
    data object ClearScreen : TerminalIntent

    /**
     * Raw bytes (control characters, escape sequences) for the foreground process of [sessionId].
     * Dropped with error haptics when no command is running, so stray bytes never reach the idle shell.
     */
    class SendRawInput(val sessionId: String, val bytes: ByteArray) : TerminalIntent {
        override fun equals(other: Any?): Boolean =
            other is SendRawInput && other.sessionId == sessionId && other.bytes.contentEquals(bytes)

        override fun hashCode(): Int = 31 * sessionId.hashCode() + bytes.contentHashCode()

        override fun toString(): String = "SendRawInput(sessionId=$sessionId, bytes=${bytes.size})"
    }
}

data class TerminalSessionState(
    val sessionId: String,
    val title: String,
    val workingDirectory: File,
    val windowsPromptDisplay: String, // e.g., "C:\\Users\\shell>"
    val stagedInput: String = "",
    val composingRange: IntRange? = null,
    val isProcessRunning: Boolean = false,
    /** Staged cursor (UTF-16 index into [stagedInput]); null means the end of the text. */
    val cursorIndex: Int? = null
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
    val lastTranslationBanner: String? = null, // Shows e.g. "[CMD -> SH] dir /a -> ls -la"
    /** Character grid geometry last reported by the terminal view. */
    val columns: Int = DEFAULT_COLUMNS,
    val rows: Int = DEFAULT_ROWS
) {
    /** The session whose scrollback and prompt are currently shown, if any. */
    val activeSession: TerminalSessionState?
        get() = sessions.firstOrNull { it.sessionId == activeSessionId }

    companion object {
        const val DEFAULT_COLUMNS = 80
        const val DEFAULT_ROWS = 24
    }
}

sealed interface TerminalSideEffect {
    data class RestartImeForSession(val sessionId: String, val restoredStagedText: String) : TerminalSideEffect
    data class HapticFeedback(val isError: Boolean) : TerminalSideEffect
}
