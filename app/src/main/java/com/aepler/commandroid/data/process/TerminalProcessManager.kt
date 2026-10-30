// SPDX-License-Identifier: GPL-3.0-only
package com.aepler.commandroid.data.process

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.io.File

data class TerminalOutputChunk(
    val sessionId: String,
    val text: String,
    val isStdErr: Boolean,
    val timestampNanos: Long = System.nanoTime()
)

interface TerminalProcessManager {
    /**
     * Starts or reuses a persistent `/system/bin/sh` interactive subshell for [sessionId]
     * or executes a translated command pipeline within [workingDir].
     */
    suspend fun startProcess(
        sessionId: String,
        posixCommand: String,
        workingDir: File,
        environment: Map<String, String>
    ): Flow<TerminalOutputChunk>

    suspend fun writeStdin(sessionId: String, inputBytes: ByteArray)

    suspend fun killProcess(sessionId: String): Boolean

    fun isAlive(sessionId: String): Boolean

    /**
     * Like [startProcess] but also exposes the command's [CommandCompletion] (exit code and the
     * shell's working directory afterwards). [CommandExecution.completion] resolves after the
     * output flow has been fully produced; `null` when the implementation cannot detect completion.
     */
    suspend fun startCommand(
        sessionId: String,
        posixCommand: String,
        workingDir: File,
        environment: Map<String, String>
    ): CommandExecution = CommandExecution(
        output = startProcess(sessionId, posixCommand, workingDir, environment),
        completion = CompletableDeferred<CommandCompletion?>(null)
    )

    /** Output a session shell produced while no command was in flight (e.g. background jobs). */
    val backgroundOutput: Flow<TerminalOutputChunk> get() = emptyFlow()
}

/** One submitted command: its output stream plus its eventual completion. */
class CommandExecution(
    val output: Flow<TerminalOutputChunk>,
    val completion: Deferred<CommandCompletion?>
)
