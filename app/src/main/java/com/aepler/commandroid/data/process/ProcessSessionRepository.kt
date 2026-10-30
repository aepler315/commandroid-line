// SPDX-License-Identifier: GPL-3.0-only
package com.aepler.commandroid.data.process

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** Result of one command execution inside a persistent session shell. */
data class CommandCompletion(
    val sessionId: String,
    val exitCode: Int,
    /** Shell working directory after the command finished (tracked for `cd`). */
    val workingDirectory: File
)

/**
 * Presentation-facing facade over [TerminalProcessManager]. Owns session lifecycle and
 * reports per-command completion (exit code + resulting working directory).
 */
interface ProcessSessionRepository {
    /** Output for every session, in arrival order. */
    val output: Flow<TerminalOutputChunk>

    /** One element per finished command. */
    val completions: Flow<CommandCompletion>

    suspend fun execute(sessionId: String, posixCommand: String, workingDir: File, environment: Map<String, String>)

    suspend fun writeStdin(sessionId: String, inputBytes: ByteArray)

    suspend fun kill(sessionId: String): Boolean

    suspend fun closeSession(sessionId: String)

    fun isRunning(sessionId: String): Boolean

    /** Session ids with at least one command in flight. */
    val runningSessions: StateFlow<Set<String>> get() = NO_RUNNING_SESSIONS

    /** Kills and forgets every session this repository knows about. */
    suspend fun closeAllSessions() {}
}

private val NO_RUNNING_SESSIONS: StateFlow<Set<String>> = MutableStateFlow<Set<String>>(emptySet()).asStateFlow()
