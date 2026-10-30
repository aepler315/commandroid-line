// SPDX-License-Identifier: GPL-3.0-only
package com.aepler.commandroid.data.process

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [TerminalProcessManager] backed by one persistent, non-interactive POSIX shell per session
 * (started with [ProcessBuilder], no PTY).
 *
 * Every submitted command is framed as
 * `command eval '<cmd>'; printf '\n__CMDL_DONE_<nonce>__ %s %s\n' "$?" "$PWD" >&8; printf '__CMDL_E_<nonce>__\n' >&9; <drain>`
 * so the shell itself reports exit status and working directory. Sentinels are stripped from
 * the output; commands of one session run strictly in submission order. Because there is no
 * PTY, Ctrl-C / Ctrl-D written to stdin are plain bytes; [killProcess] is the escape hatch.
 *
 * Isolation of the command channel:
 * - fds 8/9 are private duplicates of the original stdout/stderr pipes taken when the shell
 *   starts, so a command that redirects the shell's own fd 1/2 (`exec 2>&1`, `exec >/dev/null`)
 *   cannot swallow or misroute a sentinel.
 * - User stdin shares the pipe the shell reads commands from. After each command the shell runs
 *   `<drain>`, which discards every line up to a per-session sync marker that is written in
 *   front of the next command. Input the command never read is therefore thrown away instead of
 *   being executed as shell commands, or glued onto the next framed line.
 */
@Singleton
class ShellTerminalProcessManager @Inject constructor(
    private val config: ShellConfig
) : TerminalProcessManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Unbounded threads for the per-shell blocking stdout/stderr readers and exit waiter. They
     * hold a thread for the life of each shell, so running them on [Dispatchers.IO] (64 threads)
     * would let ~21 sessions starve every other IO call, including [killProcess].
     */
    private val blockingDispatcher = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "commandroid-shell-io").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val sessions = ConcurrentHashMap<String, ShellSession>()
    private val sessionsLock = Mutex()

    private val _backgroundOutput = MutableSharedFlow<TerminalOutputChunk>(
        extraBufferCapacity = BACKGROUND_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val backgroundOutput: Flow<TerminalOutputChunk> = _backgroundOutput.asSharedFlow()

    override suspend fun startProcess(
        sessionId: String,
        posixCommand: String,
        workingDir: File,
        environment: Map<String, String>
    ): Flow<TerminalOutputChunk> = startCommand(sessionId, posixCommand, workingDir, environment).output

    override suspend fun startCommand(
        sessionId: String,
        posixCommand: String,
        workingDir: File,
        environment: Map<String, String>
    ): CommandExecution {
        repeat(START_ATTEMPTS) {
            val session = try {
                obtainSession(sessionId, workingDir, environment)
            } catch (e: IOException) {
                return failedExecution(sessionId, workingDir, "commandroid: unable to start shell ${config.shellPath}\n")
            }
            val command = PendingCommand(
                sessionId = sessionId,
                nonce = UUID.randomUUID().toString().replace("-", ""),
                text = posixCommand,
                workingDir = workingDir,
                buffer = OutputRingBuffer(sessionId, config.maxBufferedCharsPerCommand)
            )
            // A closed queue means the shell died after lookup; retry on a fresh shell.
            if (session.queue.trySend(command).isSuccess) {
                return CommandExecution(command.buffer.asFlow(), command.completion)
            }
        }
        return failedExecution(sessionId, workingDir, "commandroid: shell exited while starting command\n")
    }

    override suspend fun writeStdin(sessionId: String, inputBytes: ByteArray) {
        val session = sessions[sessionId] ?: return
        if (inputBytes.isEmpty()) return
        // With no command in flight the bytes could only reach the idle shell's command parser.
        if (session.current == null) return
        session.write(inputBytes)
    }

    override suspend fun killProcess(sessionId: String): Boolean = withContext(Dispatchers.IO) {
        val session = sessions.remove(sessionId) ?: return@withContext false
        val wasAlive = session.process.isAlive
        session.pid?.let { killDescendants(it) }
        session.process.destroyForcibly()
        session.markDead()
        wasAlive
    }

    override fun isAlive(sessionId: String): Boolean {
        val session = sessions[sessionId] ?: return false
        return !session.dead && session.process.isAlive
    }

    /** Whether [sessionId] currently has a command in flight. */
    fun isCommandRunning(sessionId: String): Boolean = sessions[sessionId]?.current != null

    private suspend fun obtainSession(
        sessionId: String,
        workingDir: File,
        environment: Map<String, String>
    ): ShellSession = sessionsLock.withLock {
        sessions[sessionId]?.takeIf { !it.dead && it.process.isAlive }?.let { return it }
        sessions.remove(sessionId)
        val session = withContext(Dispatchers.IO) { spawn(sessionId, workingDir, environment) }
        sessions[sessionId] = session
        session
    }

    private fun spawn(sessionId: String, workingDir: File, environment: Map<String, String>): ShellSession {
        val shell = config.shellPath
        // Report the shell pid first (for best-effort child cleanup), then become the session shell.
        val builder = ProcessBuilder(shell, "-c", "printf '%s\\n' \"\$\$\"; exec \"\$0\"", shell)
        if (workingDir.isDirectory) builder.directory(workingDir)
        builder.environment().apply {
            putAll(config.baseEnvironment)
            putAll(environment)
        }
        val process = builder.start()
        val initialDir = if (workingDir.isDirectory) workingDir else File(System.getProperty("user.dir") ?: "/")
        return ShellSession(sessionId, process, initialDir).also { it.launch() }
    }

    private fun failedExecution(sessionId: String, workingDir: File, message: String): CommandExecution {
        val buffer = OutputRingBuffer(sessionId, config.maxBufferedCharsPerCommand)
        buffer.offer(message, isStdErr = true)
        buffer.close()
        return CommandExecution(
            buffer.asFlow(),
            CompletableDeferred(CommandCompletion(sessionId, EXIT_SPAWN_FAILURE, workingDir))
        )
    }

    /** Kills every descendant of [rootPid] found via /proc (own-uid processes only). */
    private fun killDescendants(rootPid: Int) {
        val descendants = findDescendants(rootPid)
        if (descendants.isEmpty()) return
        try {
            val killer = ProcessBuilder(config.shellPath, "-c", "kill -9 " + descendants.joinToString(" ") + " 2>/dev/null")
                .redirectErrorStream(true)
                .start()
            killer.inputStream.use { it.readBytes() }
            killer.waitFor(KILL_WAIT_MILLIS, TimeUnit.MILLISECONDS)
        } catch (_: IOException) {
            // Best effort: the shell itself is still destroyed by the caller.
        }
    }

    private fun findDescendants(rootPid: Int): List<Int> {
        val children = HashMap<Int, MutableList<Int>>()
        val procEntries = File("/proc").listFiles() ?: return emptyList()
        for (entry in procEntries) {
            val pid = entry.name.toIntOrNull() ?: continue
            val stat = try {
                File(entry, "stat").readText()
            } catch (_: IOException) {
                continue
            }
            val afterComm = stat.lastIndexOf(')')
            if (afterComm < 0) continue
            val fields = stat.substring(afterComm + 1).trim().split(' ')
            val ppid = fields.getOrNull(1)?.toIntOrNull() ?: continue
            children.getOrPut(ppid) { mutableListOf() }.add(pid)
        }
        val result = ArrayList<Int>()
        val queue = ArrayDeque(listOf(rootPid))
        while (queue.isNotEmpty()) {
            val next = children[queue.removeFirst()] ?: continue
            for (child in next) {
                if (child !in result) {
                    result.add(child)
                    queue.addLast(child)
                }
            }
        }
        return result
    }

    private class PendingCommand(
        val sessionId: String,
        val nonce: String,
        val text: String,
        val workingDir: File,
        val buffer: OutputRingBuffer
    ) {
        val stdoutMarker = "\n__CMDL_DONE_${nonce}__ "
        val stderrMarker = "__CMDL_E_${nonce}__\n"
        val stdoutDone = CompletableDeferred<Unit>()
        val stderrDone = CompletableDeferred<Unit>()
        val completion = CompletableDeferred<CommandCompletion?>()

        @Volatile var exitCode: Int? = null
        @Volatile var pwd: String? = null

        fun isStreamDone(isStdErr: Boolean): Boolean =
            if (isStdErr) stderrDone.isCompleted else stdoutDone.isCompleted

        fun forceStreamsDone() {
            stdoutDone.complete(Unit)
            stderrDone.complete(Unit)
        }

        /**
         * @param syncMarker per-session marker that ends the drain loop of the previous command;
         *   null for the first command of a shell (nothing to drain yet).
         */
        fun framedInput(knownPwd: File, syncMarker: String?, nextSyncMarker: String): ByteArray {
            val cd = if (workingDir.isDirectory && !sameDir(workingDir, knownPwd)) {
                "cd -- ${shQuote(workingDir.path)} 2>/dev/null; "
            } else {
                ""
            }
            val sync = if (syncMarker == null) "" else "\n$syncMarker\n"
            val line = sync + cd + "command eval ${shQuote(text)}; " +
                "printf '\\n__CMDL_DONE_%s__ %s %s\\n' '$nonce' \"\$?\" \"\$PWD\" >&$SENTINEL_STDOUT_FD; " +
                "printf '__CMDL_E_%s__\\n' '$nonce' >&$SENTINEL_STDERR_FD; " +
                "while IFS= read -r __cmdl_l; do [ \"\$__cmdl_l\" = '$nextSyncMarker' ] && break; done\n"
            return line.toByteArray(Charsets.UTF_8)
        }
    }

    private inner class ShellSession(
        val id: String,
        val process: Process,
        initialDir: File
    ) {
        val queue = Channel<PendingCommand>(Channel.UNLIMITED)
        private val stdinLock = Mutex()

        /** Marker the shell's drain loop is waiting for; null until the first command was sent. */
        private var pendingSyncMarker: String? = null
        private val syncMarker = "__CMDL_SYNC_${UUID.randomUUID().toString().replace("-", "")}__"

        @Volatile var current: PendingCommand? = null
        @Volatile var dead = false
        @Volatile var pid: Int? = null
        @Volatile var lastPwd: File = initialDir

        fun launch() {
            scope.launch(blockingDispatcher) { readStream(process.inputStream, isStdErr = false) }
            scope.launch(blockingDispatcher) { readStream(process.errorStream, isStdErr = true) }
            scope.launch(blockingDispatcher) {
                runInterruptible { process.waitFor() }
                delay(config.exitGraceMillis)
                markDead()
            }
            scope.launch { dispatch() }
        }

        suspend fun write(bytes: ByteArray) {
            withContext(Dispatchers.IO) {
                stdinLock.withLock {
                    try {
                        process.outputStream.write(bytes)
                        process.outputStream.flush()
                    } catch (_: IOException) {
                        // Shell already gone; the exit watcher completes pending work.
                    }
                }
            }
        }

        fun markDead() {
            dead = true
            sessions.remove(id, this)
            queue.close()
            current?.forceStreamsDone()
        }

        private suspend fun dispatch() {
            // Private copies of the sentinel pipes (see the class documentation).
            write("exec $SENTINEL_STDOUT_FD>&1 $SENTINEL_STDERR_FD>&2\n".toByteArray(Charsets.UTF_8))
            for (command in queue) {
                if (!dead) {
                    current = command
                    if (dead) command.forceStreamsDone()
                    write(command.framedInput(lastPwd, pendingSyncMarker, syncMarker))
                    pendingSyncMarker = syncMarker
                    command.stdoutDone.await()
                    command.stderrDone.await()
                    current = null
                }
                val exit = command.exitCode ?: shellExitCode()
                command.pwd?.takeIf { it.isNotEmpty() }?.let { lastPwd = File(it) }
                command.buffer.close()
                command.completion.complete(CommandCompletion(id, exit, lastPwd))
            }
        }

        private fun shellExitCode(): Int = try {
            if (process.waitFor(KILL_WAIT_MILLIS, TimeUnit.MILLISECONDS)) process.exitValue() else EXIT_UNKNOWN
        } catch (_: InterruptedException) {
            EXIT_UNKNOWN
        }

        private fun readStream(stream: InputStream, isStdErr: Boolean) {
            val reader = InputStreamReader(stream, Charsets.UTF_8)
            val scanner = StreamScanner(this, isStdErr)
            val buf = CharArray(READ_CHARS)
            try {
                if (!isStdErr) pid = readPidLine(reader)
                while (true) {
                    val n = reader.read(buf)
                    if (n < 0) break
                    scanner.feed(buf, n)
                }
            } catch (_: IOException) {
                // Stream closed by kill/destroy.
            } finally {
                scanner.endOfStream()
            }
        }

        private fun readPidLine(reader: InputStreamReader): Int? {
            val sb = StringBuilder()
            while (sb.length < PID_LINE_MAX) {
                val c = reader.read()
                if (c < 0 || c == '\n'.code) break
                sb.append(c.toChar())
            }
            return sb.toString().trim().toIntOrNull()
        }
    }

    /** Splits one decoded stream into command output, sentinels and idle (background) output. */
    private inner class StreamScanner(
        private val session: ShellSession,
        private val isStdErr: Boolean
    ) {
        private val pending = StringBuilder()

        fun feed(buf: CharArray, n: Int) {
            pending.appendRange(buf, 0, n)
            drain()
        }

        fun endOfStream() {
            val command = session.current
            val rest = take(pending.length)
            if (command != null && !command.isStreamDone(isStdErr)) {
                command.buffer.offer(rest, isStdErr)
                if (isStdErr) command.stderrDone.complete(Unit) else command.stdoutDone.complete(Unit)
            } else {
                emitBackground(rest)
            }
        }

        private fun drain() {
            val command = session.current
            if (command == null || command.isStreamDone(isStdErr)) {
                emitBackground(takeSurrogateSafe(pending.length))
                return
            }
            val marker = if (isStdErr) command.stderrMarker else command.stdoutMarker
            val idx = pending.indexOf(marker)
            if (idx < 0) {
                val hold = partialMarkerSuffix(marker)
                command.buffer.offer(takeSurrogateSafe(pending.length - hold), isStdErr)
                return
            }
            if (isStdErr) {
                command.buffer.offer(take(idx), true)
                pending.delete(0, marker.length)
                command.stderrDone.complete(Unit)
            } else {
                val lineEnd = pending.indexOf("\n", idx + marker.length)
                if (lineEnd < 0) {
                    // Wait for the rest of the sentinel line (exit code + pwd).
                    command.buffer.offer(take(idx), false)
                    return
                }
                val meta = pending.substring(idx + marker.length, lineEnd)
                val space = meta.indexOf(' ')
                command.exitCode = (if (space < 0) meta else meta.substring(0, space)).trim().toIntOrNull()
                command.pwd = if (space < 0) null else meta.substring(space + 1)
                command.buffer.offer(take(idx), false)
                pending.delete(0, lineEnd + 1 - idx)
                command.stdoutDone.complete(Unit)
            }
            // Anything left was produced before the next command was written.
            emitBackground(takeSurrogateSafe(pending.length))
        }

        private fun partialMarkerSuffix(marker: String): Int {
            val max = minOf(pending.length, marker.length - 1)
            for (k in max downTo 1) {
                var match = true
                val base = pending.length - k
                for (i in 0 until k) {
                    if (pending[base + i] != marker[i]) {
                        match = false
                        break
                    }
                }
                if (match) return k
            }
            return 0
        }

        private fun takeSurrogateSafe(count: Int): String {
            var end = count
            if (end > 0 && Character.isHighSurrogate(pending[end - 1])) end--
            return take(end)
        }

        private fun take(count: Int): String {
            if (count <= 0) return ""
            val text = pending.substring(0, count)
            pending.delete(0, count)
            return text
        }

        private fun emitBackground(text: String) {
            if (text.isNotEmpty()) _backgroundOutput.tryEmit(TerminalOutputChunk(session.id, text, isStdErr))
        }
    }

    companion object {
        const val EXIT_UNKNOWN = -1
        const val EXIT_SPAWN_FAILURE = 127
        private const val START_ATTEMPTS = 2
        private const val READ_CHARS = 8192
        private const val PID_LINE_MAX = 32
        private const val KILL_WAIT_MILLIS = 2_000L
        private const val BACKGROUND_BUFFER = 256
        private const val SENTINEL_STDOUT_FD = 8
        private const val SENTINEL_STDERR_FD = 9

        internal fun shQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

        private fun sameDir(a: File, b: File): Boolean = try {
            a.canonicalFile == b.canonicalFile
        } catch (_: IOException) {
            a.absoluteFile == b.absoluteFile
        }
    }
}
