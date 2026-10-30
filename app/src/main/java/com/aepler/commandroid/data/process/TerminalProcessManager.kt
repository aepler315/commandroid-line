// SPDX-License-Identifier: GPL-3.0-only
package com.aepler.commandroid.data.process

import kotlinx.coroutines.flow.Flow
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
}
