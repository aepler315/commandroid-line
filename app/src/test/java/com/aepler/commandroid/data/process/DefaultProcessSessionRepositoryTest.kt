// SPDX-License-Identifier: GPL-3.0-only
package com.aepler.commandroid.data.process

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class DefaultProcessSessionRepositoryTest {
    private lateinit var scope: CoroutineScope
    private lateinit var repository: DefaultProcessSessionRepository

    @Before
    fun setUp() {
        assumeTrue(File(ShellConfig.POSIX_SHELL).canExecute())
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        repository = DefaultProcessSessionRepository(
            ShellTerminalProcessManager(ShellConfig(shellPath = ShellConfig.POSIX_SHELL)),
            scope
        )
    }

    @After
    fun tearDown() = runBlocking {
        repository.closeAllSessions()
        scope.cancel()
    }

    @Test
    fun outputPrecedesCompletionAndRunningStateClears() = runBlocking {
        val events = mutableListOf<String>()
        withTimeout(20_000) {
            val outputJob = scope.async {
                val collected = StringBuilder()
                repository.output.transformWhile {
                    collected.append(it.text)
                    emit(it)
                    "done" !in collected
                }.toList()
            }
            val completionJob = scope.async { repository.completions.first { it.sessionId == "r1" } }
            yield()
            Thread.sleep(100) // let the collectors subscribe
            repository.execute("r1", "echo start; cd /tmp; echo done", File("/"), emptyMap())
            assertTrue(repository.isRunning("r1"))
            val chunks = outputJob.await()
            events += chunks.joinToString("") { it.text }
            val completion = completionJob.await()
            assertEquals(0, completion.exitCode)
            assertEquals(File("/tmp").canonicalFile, completion.workingDirectory.canonicalFile)
            repository.runningSessions.first { "r1" !in it }
        }
        assertTrue(events.single().startsWith("start\n"))
        assertFalse(repository.isRunning("r1"))
    }

    @Test
    fun killStopsRunningSession() = runBlocking {
        withTimeout(20_000) {
            val completion = scope.async { repository.completions.first { it.sessionId == "k" } }
            Thread.sleep(100)
            repository.execute("k", "sleep 30", File("/tmp"), emptyMap())
            Thread.sleep(200)
            assertTrue(repository.kill("k"))
            assertTrue(completion.await().exitCode != 0)
            repository.runningSessions.first { it.isEmpty() }
        }
        repository.closeSession("k")
        assertFalse(repository.isRunning("k"))
    }
}
