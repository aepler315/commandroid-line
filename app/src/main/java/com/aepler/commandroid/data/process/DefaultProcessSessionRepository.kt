// SPDX-License-Identifier: GPL-3.0-only
package com.aepler.commandroid.data.process

import com.aepler.commandroid.di.ProcessScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Merges per-command output of every session into [output] and reports each finished command on
 * [completions] (always after that command's last output chunk). Output collection runs in the
 * app-wide [ProcessScope] so it outlives activities and configuration changes.
 */
@Singleton
class DefaultProcessSessionRepository @Inject constructor(
    private val processManager: TerminalProcessManager,
    @ProcessScope private val scope: CoroutineScope
) : ProcessSessionRepository {

    /**
     * SUSPEND, not DROP_OLDEST: a slow collector must push back into each command's
     * [OutputRingBuffer], which then drops with a visible "characters dropped" marker. Dropping
     * here would lose chunks silently, and across sessions.
     */
    private val _output = MutableSharedFlow<TerminalOutputChunk>(
        extraBufferCapacity = OUTPUT_BUFFER,
        onBufferOverflow = BufferOverflow.SUSPEND
    )
    override val output: Flow<TerminalOutputChunk> = _output.asSharedFlow()

    private val _completions = MutableSharedFlow<CommandCompletion>(
        extraBufferCapacity = COMPLETION_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val completions: Flow<CommandCompletion> = _completions.asSharedFlow()

    private val inFlight = ConcurrentHashMap<String, AtomicInteger>()
    private val knownSessions = ConcurrentHashMap.newKeySet<String>()
    private val _runningSessions = MutableStateFlow<Set<String>>(emptySet())
    override val runningSessions: StateFlow<Set<String>> = _runningSessions.asStateFlow()

    init {
        scope.launch { processManager.backgroundOutput.collect { _output.emit(it) } }
    }

    override suspend fun execute(
        sessionId: String,
        posixCommand: String,
        workingDir: File,
        environment: Map<String, String>
    ) {
        knownSessions.add(sessionId)
        markStarted(sessionId)
        val execution = try {
            // Called inline so commands of one session are enqueued in submission order.
            processManager.startCommand(sessionId, posixCommand, workingDir, environment)
        } catch (t: Throwable) {
            markFinished(sessionId)
            throw t
        }
        scope.launch {
            try {
                execution.output.collect { _output.emit(it) }
                execution.completion.await()?.let { _completions.emit(it) }
            } finally {
                markFinished(sessionId)
            }
        }
    }

    override suspend fun writeStdin(sessionId: String, inputBytes: ByteArray) {
        processManager.writeStdin(sessionId, inputBytes)
    }

    override suspend fun kill(sessionId: String): Boolean = processManager.killProcess(sessionId)

    override suspend fun closeSession(sessionId: String) {
        processManager.killProcess(sessionId)
        knownSessions.remove(sessionId)
    }

    override suspend fun closeAllSessions() {
        for (sessionId in knownSessions.toList() + _runningSessions.value) closeSession(sessionId)
    }

    override fun isRunning(sessionId: String): Boolean = (inFlight[sessionId]?.get() ?: 0) > 0

    private fun markStarted(sessionId: String) {
        inFlight.computeIfAbsent(sessionId) { AtomicInteger() }.incrementAndGet()
        _runningSessions.update { it + sessionId }
    }

    private fun markFinished(sessionId: String) {
        val remaining = inFlight[sessionId]?.decrementAndGet() ?: 0
        if (remaining <= 0) {
            inFlight.remove(sessionId)
            _runningSessions.update { it - sessionId }
        }
    }

    private companion object {
        const val OUTPUT_BUFFER = 1024
        const val COMPLETION_BUFFER = 64
    }
}
