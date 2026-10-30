// SPDX-License-Identifier: GPL-3.0-only
package com.aepler.commandroid.data.process

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** STUB: ProcessBuilder-backed implementation, completed by the data workstream. */
@Singleton
class ShellTerminalProcessManager @Inject constructor() : TerminalProcessManager {
    override suspend fun startProcess(
        sessionId: String,
        posixCommand: String,
        workingDir: File,
        environment: Map<String, String>
    ): Flow<TerminalOutputChunk> = emptyFlow()

    override suspend fun writeStdin(sessionId: String, inputBytes: ByteArray) {}

    override suspend fun killProcess(sessionId: String): Boolean = false

    override fun isAlive(sessionId: String): Boolean = false
}
