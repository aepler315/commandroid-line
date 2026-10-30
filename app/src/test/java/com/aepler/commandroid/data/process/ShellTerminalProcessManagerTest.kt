// SPDX-License-Identifier: GPL-3.0-only
package com.aepler.commandroid.data.process

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class ShellTerminalProcessManagerTest {
    private val tmp = File("/tmp")
    private lateinit var manager: ShellTerminalProcessManager
    private val started = mutableListOf<String>()

    @Before
    fun setUp() {
        assumeTrue("POSIX shell required", File(ShellConfig.POSIX_SHELL).canExecute())
        manager = ShellTerminalProcessManager(ShellConfig(shellPath = ShellConfig.POSIX_SHELL))
    }

    @After
    fun tearDown() = runBlocking {
        for (id in started) manager.killProcess(id)
    }

    private suspend fun run(sessionId: String, command: String, dir: File = tmp): Pair<List<TerminalOutputChunk>, CommandCompletion?> {
        started += sessionId
        val execution = manager.startCommand(sessionId, command, dir, emptyMap())
        return withTimeout(TIMEOUT) {
            val chunks = execution.output.toList()
            chunks to execution.completion.await()
        }
    }

    private fun List<TerminalOutputChunk>.stdout() = filter { !it.isStdErr }.joinToString("") { it.text }
    private fun List<TerminalOutputChunk>.stderr() = filter { it.isStdErr }.joinToString("") { it.text }

    @Test
    fun echoOutputIsCapturedWithoutSentinel() = runBlocking {
        val (chunks, completion) = run("s1", "echo hello")
        assertEquals("hello\n", chunks.stdout())
        assertEquals("", chunks.stderr())
        assertEquals(0, completion!!.exitCode)
        assertTrue(chunks.none { "__CMDL_" in it.text })
    }

    @Test
    fun outputWithoutTrailingNewlineIsPreserved() = runBlocking {
        val (chunks, _) = run("s1", "printf abc")
        assertEquals("abc", chunks.stdout())
    }

    @Test
    fun stderrIsFlagged() = runBlocking {
        val (chunks, completion) = run("s1", "echo oops >&2; echo fine")
        assertEquals("oops\n", chunks.stderr())
        assertEquals("fine\n", chunks.stdout())
        assertEquals(0, completion!!.exitCode)
    }

    @Test
    fun exitCodeAndPwdAreReportedAndPersist() = runBlocking {
        val (_, failed) = run("s1", "false")
        assertEquals(1, failed!!.exitCode)

        val (_, cd) = run("s1", "cd /tmp", dir = File("/"))
        assertEquals(0, cd!!.exitCode)
        assertEquals(File("/tmp").canonicalFile, cd.workingDirectory.canonicalFile)

        val (chunks, code) = run("s1", "pwd; exit_code_test() { return 42; }; exit_code_test", dir = cd.workingDirectory)
        assertEquals(File("/tmp").canonicalPath, File(chunks.stdout().trim()).canonicalPath)
        assertEquals(42, code!!.exitCode)
    }

    @Test
    fun syntaxErrorDoesNotKillTheShell() = runBlocking {
        val (_, broken) = run("s1", "if then fi (")
        assertNotNull(broken)
        assertTrue(broken!!.exitCode != 0)
        val (chunks, ok) = run("s1", "echo still-here")
        assertEquals("still-here\n", chunks.stdout())
        assertEquals(0, ok!!.exitCode)
    }

    @Test
    fun quotesInCommandsSurvive() = runBlocking {
        val (chunks, _) = run("s1", "echo 'it'\"'\"'s' \"a  b\"")
        assertEquals("it's a  b\n", chunks.stdout())
    }

    @Test
    fun multibyteUtf8IsIntact() = runBlocking {
        val text = "rocket 🚀 fire 🔥 é中"
        val (chunks, _) = run("s1", "printf '%s\\n' '$text'")
        assertEquals("$text\n", chunks.stdout())
        // Large emoji payload crossing many read boundaries.
        val (big, _) = run("s1", "i=0; while [ \$i -lt 3000 ]; do printf '\\360\\237\\232\\200'; i=\$((i+1)); done")
        val out = big.stdout()
        assertEquals(6000, out.length)
        assertEquals("🚀".repeat(3000), out)
        for (chunk in big) {
            assertFalse(chunk.text.isNotEmpty() && Character.isLowSurrogate(chunk.text.first()))
            assertFalse(chunk.text.isNotEmpty() && Character.isHighSurrogate(chunk.text.last()))
        }
    }

    @Test
    fun killTerminatesLongRunningCommand() = runBlocking {
        started += "s1"
        val execution = manager.startCommand("s1", "sleep 30", tmp, emptyMap())
        delay(300)
        assertTrue(manager.isAlive("s1"))
        val startNanos = System.nanoTime()
        assertTrue(manager.killProcess("s1"))
        val completion = withTimeout(5_000) {
            execution.output.toList()
            execution.completion.await()
        }
        assertTrue((System.nanoTime() - startNanos) / 1_000_000 < 5_000)
        assertTrue(completion!!.exitCode != 0)
        assertFalse(manager.isAlive("s1"))
        assertFalse(manager.killProcess("s1"))

        // A new shell is started on demand afterwards.
        val (chunks, _) = run("s1", "echo back")
        assertEquals("back\n", chunks.stdout())
    }

    @Test
    fun floodIsBoundedAndCompletes() = runBlocking {
        val small = ShellTerminalProcessManager(ShellConfig(shellPath = ShellConfig.POSIX_SHELL, maxBufferedCharsPerCommand = 4096))
        try {
            val execution = small.startCommand(
                "flood",
                "i=0; while [ \$i -lt 50000 ]; do echo line\$i; i=\$((i+1)); done; echo END",
                tmp,
                emptyMap()
            )
            var chars = 0L
            var sawDropMarker = false
            var last = ""
            withTimeout(60_000) {
                execution.output.collect {
                    if (it.text.startsWith(OutputRingBuffer.DROPPED_MARKER_PREFIX)) sawDropMarker = true else chars += it.text.length
                    if (!it.text.startsWith(OutputRingBuffer.DROPPED_MARKER_PREFIX)) last = (last + it.text).takeLast(16)
                    delay(1) // slow consumer
                }
                assertEquals(0, execution.completion.await()!!.exitCode)
            }
            assertTrue("expected drops with a slow consumer", sawDropMarker)
            assertTrue(last.endsWith("END\n"))
            assertTrue(chars < 50_000L * 10)
        } finally {
            small.killProcess("flood")
        }
    }

    @Test
    fun floodWithFastConsumerDeliversEverything() = runBlocking {
        val (chunks, completion) = run("s1", "i=0; while [ \$i -lt 50000 ]; do echo \$i; i=\$((i+1)); done")
        assertEquals(0, completion!!.exitCode)
        val lines = chunks.stdout().lines().filter { it.isNotEmpty() }
        assertEquals(50_000, lines.size)
        assertEquals("49999", lines.last())
    }

    @Test
    fun sessionsAreIsolated() = runBlocking {
        run("a", "cd /tmp; X=from_a")
        run("b", "cd /; X=from_b")
        val (a, ca) = run("a", "echo \$X", dir = File("/tmp"))
        val (b, cb) = run("b", "echo \$X", dir = File("/"))
        assertEquals("from_a\n", a.stdout())
        assertEquals("from_b\n", b.stdout())
        assertEquals(File("/tmp").canonicalFile, ca!!.workingDirectory.canonicalFile)
        assertEquals(File("/"), cb!!.workingDirectory)
        manager.killProcess("a")
        assertFalse(manager.isAlive("a"))
        assertTrue(manager.isAlive("b"))
    }

    @Test
    fun writeStdinFeedsRunningCommand() = runBlocking {
        started += "s1"
        val execution = manager.startCommand("s1", "read line; echo got:\$line", tmp, emptyMap())
        delay(200)
        manager.writeStdin("s1", "abc\n".toByteArray())
        val chunks = withTimeout(TIMEOUT) { execution.output.toList() }
        assertEquals("got:abc\n", chunks.stdout())
    }

    @Test
    fun unreadStdinIsDiscardedInsteadOfExecutedByTheIdleShell() = runBlocking {
        val background = mutableListOf<TerminalOutputChunk>()
        val collector = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            manager.backgroundOutput.collect { synchronized(background) { background += it } }
        }
        try {
            started += "s1"
            val sleeping = manager.startCommand("s1", "sleep 0.5", tmp, emptyMap())
            delay(150)
            // A typed answer plus an arrow-key escape sequence the command never reads.
            manager.writeStdin("s1", "y\n\u001b[A".toByteArray())
            withTimeout(TIMEOUT) {
                sleeping.output.toList()
                assertEquals(0, sleeping.completion.await()!!.exitCode)
            }
            val (chunks, completion) = run("s1", "echo hi")
            assertEquals("hi\n", chunks.stdout())
            assertEquals("", chunks.stderr())
            assertEquals(0, completion!!.exitCode)
            delay(100)
            val stray = synchronized(background) { background.joinToString("") { it.text } }
            assertFalse("stale input was executed: $stray", "not found" in stray)
        } finally {
            collector.cancel()
        }
    }

    @Test
    fun stdinWithoutRunningCommandIsDropped() = runBlocking {
        run("s1", "true")
        manager.writeStdin("s1", "echo leaked\n".toByteArray())
        val (chunks, completion) = run("s1", "echo ok")
        assertEquals("ok\n", chunks.stdout())
        assertEquals(0, completion!!.exitCode)
    }

    @Test
    fun redirectingTheShellsOwnFdsDoesNotHangTheSession() = runBlocking {
        val background = mutableListOf<TerminalOutputChunk>()
        val collector = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            manager.backgroundOutput.collect { synchronized(background) { background += it } }
        }
        try {
            val (_, merged) = run("s1", "exec 2>&1; echo merged")
            assertEquals(0, merged!!.exitCode)
            val (_, silenced) = run("s1", "exec >/dev/null; echo hidden")
            assertEquals(0, silenced!!.exitCode)
            val (_, after) = run("s1", "true")
            assertEquals(0, after!!.exitCode)
            delay(100)
            assertTrue(synchronized(background) { background.none { "__CMDL_" in it.text } })
        } finally {
            collector.cancel()
        }
    }

    @Test
    fun manySessionsDoNotStarveIo() = runBlocking {
        // Each shell pins blocking reader threads; they must not come from the shared IO pool.
        val ids = (1..30).map { "many$it" }
        for (id in ids) {
            val (chunks, _) = run(id, "echo $id")
            assertEquals("$id\n", chunks.stdout())
        }
        withTimeout(TIMEOUT) { assertTrue(manager.killProcess(ids.first())) }
    }

    @Test
    fun environmentIsAppliedAtStart() = runBlocking {
        started += "env"
        val execution = manager.startCommand("env", "echo \$CMDL_TEST_VAR", tmp, mapOf("CMDL_TEST_VAR" to "v1"))
        val chunks = withTimeout(TIMEOUT) { execution.output.toList() }
        assertEquals("v1\n", chunks.stdout())
    }

    @Test
    fun commandsRunInSubmissionOrder() = runBlocking {
        started += "s1"
        val first = manager.startCommand("s1", "sleep 0.3; echo one", tmp, emptyMap())
        val second = manager.startCommand("s1", "echo two", tmp, emptyMap())
        withTimeout(TIMEOUT) {
            assertEquals("one\n", first.output.toList().stdout())
            assertEquals("two\n", second.output.toList().stdout())
        }
    }

    @Test
    fun spawnFailureReportsError() = runBlocking {
        val broken = ShellTerminalProcessManager(ShellConfig(shellPath = "/nonexistent/sh"))
        val execution = broken.startCommand("x", "echo hi", tmp, emptyMap())
        val chunks = withTimeout(TIMEOUT) { execution.output.toList() }
        assertTrue(chunks.all { it.isStdErr })
        assertEquals(ShellTerminalProcessManager.EXIT_SPAWN_FAILURE, execution.completion.await()!!.exitCode)
        assertFalse(broken.isAlive("x"))
    }

    private companion object {
        const val TIMEOUT = 20_000L
    }
}
