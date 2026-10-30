// SPDX-License-Identifier: GPL-3.0-only
package com.aepler.commandroid.data.process

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** STUB: completed by the data workstream. */
@Singleton
class DefaultProcessSessionRepository @Inject constructor(
    private val processManager: TerminalProcessManager
) : ProcessSessionRepository {
    override val output: Flow<TerminalOutputChunk> = emptyFlow()
    override val completions: Flow<CommandCompletion> = emptyFlow()
    override suspend fun execute(sessionId: String, posixCommand: String, workingDir: File, environment: Map<String, String>) {}
    override suspend fun writeStdin(sessionId: String, inputBytes: ByteArray) {}
    override suspend fun kill(sessionId: String): Boolean = false
    override suspend fun closeSession(sessionId: String) {}
    override fun isRunning(sessionId: String): Boolean = false
}
