// SPDX-License-Identifier: GPL-3.0-only
package com.aepler.commandroid.presentation

import com.aepler.commandroid.data.process.CommandCompletion
import com.aepler.commandroid.data.process.TerminalOutputChunk
import java.io.File

/** A command handed to the shell whose completion has not been reported yet. */
data class PendingCommand(
    val isDirectoryChange: Boolean,
    val submittedWorkingDirectory: File,
    val predictedWorkingDirectory: File? = null
)

/** Bytes of stdout/stderr received after the last newline, per stream. */
data class PartialOutput(val stdout: String = "", val stderr: String = "") {
    fun isEmpty(): Boolean = stdout.isEmpty() && stderr.isEmpty()
}

/**
 * Full presentation model. [TerminalViewState] is a projection of it containing only the
 * active session's scrollback.
 */
data class TerminalModel(
    val sessions: List<TerminalSessionState> = emptyList(),
    val activeSessionId: String = "",
    val linesBySession: Map<String, List<TerminalRenderedLine>> = emptyMap(),
    val partialOutput: Map<String, PartialOutput> = emptyMap(),
    val pendingCommands: Map<String, PendingCommand> = emptyMap(),
    val lastTranslationBanner: String? = null,
    val columns: Int = TerminalViewState.DEFAULT_COLUMNS,
    val rows: Int = TerminalViewState.DEFAULT_ROWS,
    val nextSessionNumber: Int = 1
) {
    val activeSession: TerminalSessionState?
        get() = sessions.firstOrNull { it.sessionId == activeSessionId }

    fun session(sessionId: String): TerminalSessionState? = sessions.firstOrNull { it.sessionId == sessionId }
}

/** Pure state transitions for the terminal MVI loop. Nothing here performs I/O. */
object TerminalReducer {
    const val MAX_SCROLLBACK_LINES = 5000
    const val SESSION_ID_PREFIX = "session-"
    const val BANNER_PREFIX = "[CMD -> SH]"

    /** Longest unterminated output kept per stream before it is committed as a line anyway. */
    const val MAX_PARTIAL_CHARS = 16 * 1024

    fun toViewState(model: TerminalModel): TerminalViewState {
        val active = model.activeSessionId
        val committed = model.linesBySession[active].orEmpty()
        val partial = model.partialOutput[active]
        val lines = if (partial == null || partial.isEmpty()) {
            committed
        } else {
            buildList(committed.size + 2) {
                addAll(committed)
                if (partial.stdout.isNotEmpty()) add(TerminalRenderedLine(active, partial.stdout, TerminalLineKind.STDOUT))
                if (partial.stderr.isNotEmpty()) add(TerminalRenderedLine(active, partial.stderr, TerminalLineKind.STDERR))
            }
        }
        return TerminalViewState(
            sessions = model.sessions,
            activeSessionId = active,
            terminalLines = lines,
            lastTranslationBanner = model.lastTranslationBanner,
            columns = model.columns,
            rows = model.rows
        )
    }

    fun newSessionState(sessionId: String, title: String, workingDirectory: File): TerminalSessionState =
        TerminalSessionState(
            sessionId = sessionId,
            title = title,
            workingDirectory = workingDirectory,
            windowsPromptDisplay = WindowsPromptFormatter.format(workingDirectory)
        )

    /** Adds a session and makes it active. */
    fun createSession(model: TerminalModel, workingDirectory: File): TerminalModel {
        var number = model.nextSessionNumber
        while (model.session("$SESSION_ID_PREFIX$number") != null) number++
        val session = newSessionState("$SESSION_ID_PREFIX$number", "Session $number", workingDirectory)
        return model.copy(
            sessions = model.sessions + session,
            activeSessionId = session.sessionId,
            linesBySession = model.linesBySession + (session.sessionId to emptyList()),
            nextSessionNumber = number + 1
        )
    }

    /** Activates [sessionId]; unknown ids leave the model unchanged. */
    fun switchSession(model: TerminalModel, sessionId: String): TerminalModel =
        if (model.session(sessionId) == null) model else model.copy(activeSessionId = sessionId)

    /** Session after the active one, wrapping around; null when fewer than two sessions exist. */
    fun nextSessionId(model: TerminalModel): String? {
        if (model.sessions.size < 2) return null
        val index = model.sessions.indexOfFirst { it.sessionId == model.activeSessionId }
        return model.sessions[(index + 1).mod(model.sessions.size)].sessionId
    }

    /**
     * Removes [sessionId]. If it was active, activates its neighbour, or a freshly created session
     * in [fallbackWorkingDirectory] when it was the last one.
     */
    fun closeSession(model: TerminalModel, sessionId: String, fallbackWorkingDirectory: File): TerminalModel {
        val index = model.sessions.indexOfFirst { it.sessionId == sessionId }
        if (index < 0) return model
        val remaining = model.sessions.filterNot { it.sessionId == sessionId }
        val trimmed = model.copy(
            sessions = remaining,
            linesBySession = model.linesBySession - sessionId,
            partialOutput = model.partialOutput - sessionId,
            pendingCommands = model.pendingCommands - sessionId
        )
        if (remaining.isEmpty()) return createSession(trimmed.copy(activeSessionId = ""), fallbackWorkingDirectory)
        if (model.activeSessionId != sessionId) return trimmed
        return trimmed.copy(activeSessionId = remaining[index.coerceAtMost(remaining.lastIndex)].sessionId)
    }

    fun updateStagedInput(
        model: TerminalModel,
        stagedText: String,
        composingRange: IntRange?,
        cursorIndex: Int? = null
    ): TerminalModel =
        updateSession(model, model.activeSessionId) {
            it.copy(
                stagedInput = stagedText,
                composingRange = composingRange,
                cursorIndex = cursorIndex?.coerceIn(0, stagedText.length)
            )
        }

    fun resize(model: TerminalModel, columns: Int, rows: Int): TerminalModel =
        if (columns <= 0 || rows <= 0) model else model.copy(columns = columns, rows = rows)

    fun clearScreen(model: TerminalModel, sessionId: String): TerminalModel {
        if (model.session(sessionId) == null) return model
        return model.copy(
            linesBySession = model.linesBySession + (sessionId to emptyList()),
            partialOutput = model.partialOutput - sessionId
        )
    }

    fun appendLines(
        model: TerminalModel,
        sessionId: String,
        lines: List<TerminalRenderedLine>,
        maxLines: Int = MAX_SCROLLBACK_LINES
    ): TerminalModel {
        if (lines.isEmpty() || model.session(sessionId) == null) return model
        val combined = model.linesBySession[sessionId].orEmpty() + lines
        val capped = if (combined.size > maxLines) combined.subList(combined.size - maxLines, combined.size).toList() else combined
        return model.copy(linesBySession = model.linesBySession + (sessionId to capped))
    }

    fun appendText(model: TerminalModel, sessionId: String, text: String, kind: TerminalLineKind): TerminalModel =
        appendLines(model, sessionId, text.split('\n').map { TerminalRenderedLine(sessionId, it.removeSuffix("\r"), kind) })

    /**
     * Splits a raw output chunk on newlines. Text after the last newline is buffered per session
     * and stream until a later chunk (or command completion) terminates it.
     */
    fun appendOutput(
        model: TerminalModel,
        chunk: TerminalOutputChunk,
        maxLines: Int = MAX_SCROLLBACK_LINES
    ): TerminalModel {
        val sessionId = chunk.sessionId
        if (model.session(sessionId) == null || chunk.text.isEmpty()) return model
        val partial = model.partialOutput[sessionId] ?: PartialOutput()
        val pending = (if (chunk.isStdErr) partial.stderr else partial.stdout) + chunk.text
        val segments = pending.split('\n')
        val kind = if (chunk.isStdErr) TerminalLineKind.STDERR else TerminalLineKind.STDOUT
        val complete = segments.dropLast(1).mapTo(ArrayList()) { TerminalRenderedLine(sessionId, overwriteCarriageReturns(it), kind) }
        var remainder = keepAfterCarriageReturn(segments.last())
        // Bound the unterminated tail (progress output without newlines would otherwise grow forever).
        while (remainder.length > MAX_PARTIAL_CHARS) {
            var cut = MAX_PARTIAL_CHARS
            if (Character.isHighSurrogate(remainder[cut - 1])) cut--
            complete += TerminalRenderedLine(sessionId, remainder.substring(0, cut), kind)
            remainder = remainder.substring(cut)
        }
        val updatedPartial = if (chunk.isStdErr) partial.copy(stderr = remainder) else partial.copy(stdout = remainder)
        return appendLines(model, sessionId, complete, maxLines)
            .copy(partialOutput = model.partialOutput + (sessionId to updatedPartial))
    }

    /** A completed line shows what a terminal would: the text after its last carriage return. */
    private fun overwriteCarriageReturns(line: String): String {
        val trimmed = line.removeSuffix("\r")
        val cr = trimmed.lastIndexOf('\r')
        return if (cr < 0) trimmed else trimmed.substring(cr + 1)
    }

    /**
     * Unterminated text keeps only what follows its last carriage return. A trailing '\r' is kept
     * (with the text before it) because it may be the first half of a "\r\n" split across chunks.
     */
    private fun keepAfterCarriageReturn(text: String): String {
        val searchEnd = if (text.endsWith('\r')) text.length - 2 else text.length - 1
        if (searchEnd < 0) return text
        val cr = text.lastIndexOf('\r', searchEnd)
        return if (cr < 0) text else text.substring(cr + 1)
    }

    /** Commits any unterminated output of [sessionId] as full lines. */
    fun flushPartialOutput(model: TerminalModel, sessionId: String): TerminalModel {
        val partial = model.partialOutput[sessionId] ?: return model
        val lines = buildList {
            if (partial.stdout.isNotEmpty()) add(TerminalRenderedLine(sessionId, overwriteCarriageReturns(partial.stdout), TerminalLineKind.STDOUT))
            if (partial.stderr.isNotEmpty()) add(TerminalRenderedLine(sessionId, overwriteCarriageReturns(partial.stderr), TerminalLineKind.STDERR))
        }
        return appendLines(model, sessionId, lines).copy(partialOutput = model.partialOutput - sessionId)
    }

    /** Appends the submitted line, prefixed with the CMD prompt unless it is stdin for a running job. */
    fun echoCommand(model: TerminalModel, sessionId: String, rawCommand: String, withPrompt: Boolean = true): TerminalModel {
        val session = model.session(sessionId) ?: return model
        if (!withPrompt) return echoAfterPartialOutput(model, sessionId, rawCommand)
        val text = if (rawCommand.isEmpty()) session.windowsPromptDisplay else "${session.windowsPromptDisplay} $rawCommand"
        return appendLines(
            flushPartialOutput(model, sessionId),
            sessionId,
            listOf(TerminalRenderedLine(sessionId, text, TerminalLineKind.COMMAND_ECHO))
        )
    }

    /** Appends the "^C" a terminal shows when the running job is interrupted. */
    fun echoInterrupt(model: TerminalModel, sessionId: String): TerminalModel =
        echoAfterPartialOutput(model, sessionId, "^C")

    /**
     * Echoes [text] the way a terminal does: after a pending unterminated stdout line such as an
     * interactive prompt ("Name: "), on the same line, then ends that line. Pending stderr is
     * committed first so nothing is reordered.
     */
    private fun echoAfterPartialOutput(model: TerminalModel, sessionId: String, text: String): TerminalModel {
        if (model.session(sessionId) == null) return model
        val partial = model.partialOutput[sessionId] ?: PartialOutput()
        val lines = buildList {
            if (partial.stderr.isNotEmpty()) add(TerminalRenderedLine(sessionId, overwriteCarriageReturns(partial.stderr), TerminalLineKind.STDERR))
            add(TerminalRenderedLine(sessionId, overwriteCarriageReturns(partial.stdout) + text, TerminalLineKind.COMMAND_ECHO))
        }
        return appendLines(model, sessionId, lines).copy(partialOutput = model.partialOutput - sessionId)
    }

    /** Clears the staged prompt of [sessionId] after its line was submitted. */
    fun clearStagedInput(model: TerminalModel, sessionId: String): TerminalModel =
        updateSession(model, sessionId) { it.copy(stagedInput = "", composingRange = null, cursorIndex = null) }

    fun bannerText(originalCommand: String, posixCommand: String, explanation: String): String {
        val trimmed = explanation.trim()
        if (trimmed.startsWith(BANNER_PREFIX)) return trimmed
        val base = "$BANNER_PREFIX ${originalCommand.trim()} -> $posixCommand"
        return if (trimmed.isEmpty()) base else "$base  # $trimmed"
    }

    fun showBanner(model: TerminalModel, sessionId: String, banner: String?): TerminalModel {
        val withLine = if (banner == null) model else
            appendLines(model, sessionId, listOf(TerminalRenderedLine(sessionId, banner, TerminalLineKind.TRANSLATION_BANNER)))
        return withLine.copy(lastTranslationBanner = banner)
    }

    fun appendDiagnostic(model: TerminalModel, sessionId: String, messages: List<String>): TerminalModel =
        appendLines(model, sessionId, messages.map { TerminalRenderedLine(sessionId, it, TerminalLineKind.DIAGNOSTIC) })

    fun startCommand(model: TerminalModel, sessionId: String, pending: PendingCommand): TerminalModel =
        updateSession(model, sessionId) { it.copy(isProcessRunning = true) }
            .copy(pendingCommands = model.pendingCommands + (sessionId to pending))

    /** Marks the command finished; a successful directory change updates the session's cwd and prompt. */
    fun completeCommand(model: TerminalModel, completion: CommandCompletion): TerminalModel {
        val sessionId = completion.sessionId
        if (model.session(sessionId) == null) return model
        val pending = model.pendingCommands[sessionId]
        val flushed = flushPartialOutput(model, sessionId).copy(pendingCommands = model.pendingCommands - sessionId)
        return updateSession(flushed, sessionId) { session ->
            val stopped = session.copy(isProcessRunning = false)
            if (pending == null || !pending.isDirectoryChange || completion.exitCode != 0) {
                stopped
            } else {
                val reported = completion.workingDirectory
                val target = when {
                    reported != pending.submittedWorkingDirectory -> reported
                    pending.predictedWorkingDirectory != null -> pending.predictedWorkingDirectory
                    else -> reported
                }
                stopped.copy(workingDirectory = target, windowsPromptDisplay = WindowsPromptFormatter.format(target))
            }
        }
    }

    fun markStopped(model: TerminalModel, sessionId: String): TerminalModel =
        updateSession(model, sessionId) { it.copy(isProcessRunning = false) }
            .copy(pendingCommands = model.pendingCommands - sessionId)

    /** True when the CMD line (or its translation) changes directory. */
    fun isDirectoryChange(rawCommand: String, posixCommand: String, updatedWorkingDirectory: File?): Boolean {
        if (updatedWorkingDirectory != null) return true
        val verb = rawCommand.trim().takeWhile { !it.isWhitespace() && it != '\\' && it != '/' && it != '.' }.lowercase()
        if (verb == "cd" || verb == "chdir" || verb == "pushd" || verb == "popd") return true
        val posix = posixCommand.trim()
        return posix == "cd" || posix.startsWith("cd ")
    }

    private inline fun updateSession(
        model: TerminalModel,
        sessionId: String,
        transform: (TerminalSessionState) -> TerminalSessionState
    ): TerminalModel {
        if (model.session(sessionId) == null) return model
        return model.copy(sessions = model.sessions.map { if (it.sessionId == sessionId) transform(it) else it })
    }
}
