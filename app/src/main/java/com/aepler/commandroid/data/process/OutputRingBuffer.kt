// SPDX-License-Identifier: GPL-3.0-only
package com.aepler.commandroid.data.process

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Bounded single-consumer output buffer for one command. Producers never block: once more than
 * [maxChars] undelivered characters are queued, the oldest chunks are dropped and the consumer
 * later receives one [droppedMarker] chunk in their place. This keeps a flooding command
 * (>10k lines/sec) from exhausting memory when the UI collects slower than the shell writes.
 */
internal class OutputRingBuffer(
    private val sessionId: String,
    private val maxChars: Int
) {
    private val lock = Any()
    private val chunks = ArrayDeque<TerminalOutputChunk>()
    private var bufferedChars = 0
    private var droppedChars = 0L
    private var closed = false
    private val signal = Channel<Unit>(Channel.CONFLATED)

    fun offer(text: String, isStdErr: Boolean) {
        if (text.isEmpty()) return
        synchronized(lock) {
            if (closed) return
            chunks.addLast(TerminalOutputChunk(sessionId, text, isStdErr))
            bufferedChars += text.length
            while (bufferedChars > maxChars && chunks.size > 1) {
                val dropped = chunks.removeFirst()
                bufferedChars -= dropped.text.length
                droppedChars += dropped.text.length
            }
            if (bufferedChars > maxChars) {
                val only = chunks.removeFirst()
                var start = only.text.length - maxChars
                if (start < only.text.length && Character.isLowSurrogate(only.text[start])) start++
                droppedChars += start
                val kept = only.copy(text = only.text.substring(start))
                chunks.addLast(kept)
                bufferedChars = kept.text.length
            }
        }
        signal.trySend(Unit)
    }

    /** No more output will be offered; the flow completes once drained. */
    fun close() {
        synchronized(lock) { closed = true }
        signal.trySend(Unit)
    }

    /** Single-use cold flow draining this buffer until [close]. */
    fun asFlow(): Flow<TerminalOutputChunk> = flow {
        while (true) {
            val batch: List<TerminalOutputChunk>
            val dropped: Long
            val done: Boolean
            synchronized(lock) {
                batch = chunks.toList()
                chunks.clear()
                bufferedChars = 0
                dropped = droppedChars
                droppedChars = 0
                done = closed
            }
            if (dropped > 0) emit(TerminalOutputChunk(sessionId, droppedMarker(dropped), isStdErr = true))
            for (chunk in batch) emit(chunk)
            if (done) break
            signal.receive()
        }
    }

    companion object {
        const val DROPPED_MARKER_PREFIX = "\n[commandroid: "

        fun droppedMarker(droppedChars: Long): String =
            "$DROPPED_MARKER_PREFIX$droppedChars characters of output dropped]\n"
    }
}
