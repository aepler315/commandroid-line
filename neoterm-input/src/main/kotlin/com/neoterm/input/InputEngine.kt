// SPDX-License-Identifier: GPL-3.0-only
package com.neoterm.input

interface Draft {
    val text: String
    val composing: Boolean
    fun finishComposition()
    fun clear()
}

interface TerminalTarget {
    val identity: String
    val running: Boolean
    val bracketedPaste: Boolean
    fun write(text: String)
}

enum class InputMode { AUTO, TEXT, TERMINAL }
enum class InputPhase { DIRECT, EMPTY, EDITING, COMPOSING, TRANSMITTING }
enum class SendResult { SENT, EMPTY, DIRECT, DISCONNECTED, UNSAFE_CONTROL, INVALID_UNICODE,
    BRACKETED_PASTE_REQUIRED, WRITE_FAILED, REFUSED }

/** Outcome of an optional rewrite applied to the draft at Transmit (for example CMD -> POSIX). */
sealed interface Rewrite {
    /** Text to send instead of the draft. It is validated like any draft. */
    data class Text(val text: String) : Rewrite
    /** Nothing is sent and the draft is kept. [reason] is shown to the user, never logged. */
    data class Refused(val reason: String) : Rewrite
}

/** Rewrites a draft at Transmit. Runs on the engine's thread; must not write to the terminal. */
fun interface TransmitTransform {
    fun rewrite(draft: String): Rewrite
}

/** Main-thread confined. Owns lifecycle only; Draft owns all text and selection. */
class InputEngine(private val draft: Draft) {
    var mode = InputMode.AUTO
        private set
    private var explicitComposer = false
    private var sessionIdentity: String? = null
    private var generation = 0L
    private var transmitting = false

    /** Optional Transmit rewrite. Only Transmit applies it; Submit and direct keys never do. */
    var transform: TransmitTransform? = null
    /** User-facing reason for the most recent [SendResult.REFUSED], cleared by the next Transmit. */
    var lastRefusal: String? = null
        private set

    val isComposing: Boolean
        get() = mode == InputMode.TEXT || (mode == InputMode.AUTO && explicitComposer)
    val phase: InputPhase
        get() = when {
            transmitting -> InputPhase.TRANSMITTING
            !isComposing -> InputPhase.DIRECT
            draft.composing -> InputPhase.COMPOSING
            draft.text.isEmpty() -> InputPhase.EMPTY
            else -> InputPhase.EDITING
        }

    fun setMode(value: InputMode) {
        if (mode == value) return
        generation++
        draft.finishComposition()
        mode = value
        explicitComposer = false
    }

    fun openComposer() {
        if (mode == InputMode.TERMINAL) return
        explicitComposer = true
    }

    fun bindSession(identity: String?) {
        if (identity == sessionIdentity) return
        generation++
        draft.clear()
        explicitComposer = false
        sessionIdentity = identity
    }

    fun discardDraft() {
        generation++
        draft.clear()
    }

    fun newConnection(): Long = ++generation

    fun applyImeEdit(connection: Long, operation: () -> Boolean): Boolean =
        connection == generation && isComposing && !transmitting && operation()

    fun transmit(target: TerminalTarget?): SendResult {
        if (!isComposing || transmitting) return SendResult.DIRECT
        if (!connected(target)) return SendResult.DISCONNECTED
        draft.finishComposition()
        lastRefusal = null
        val draftText = draft.text
        if (draftText.isEmpty()) return SendResult.EMPTY
        validate(draftText)?.let { return it }
        val text = when (val rewrite = rewrite(draftText)) {
            is Rewrite.Text -> rewrite.text
            is Rewrite.Refused -> { lastRefusal = rewrite.reason; return SendResult.REFUSED }
        }
        if (text.isEmpty()) return SendResult.EMPTY
        // A transform's output is held to the same rules as typed text.
        validate(text)?.let { return it }
        val bracketed = target!!.bracketedPaste
        if (!bracketed && (text.contains('\n') || text.contains('\t')))
            return SendResult.BRACKETED_PASTE_REQUIRED
        val payload = if (bracketed) "\u001b[200~$text\u001b[201~" else text
        // Invalidate before handoff: late/reentrant IME operations cannot replay a sent draft.
        generation++
        transmitting = true
        return try {
            target.write(payload)
            draft.clear()
            SendResult.SENT
        } catch (_: Exception) {
            // Never log transport exceptions: third-party errors may contain input.
            SendResult.WRITE_FAILED
        } finally {
            transmitting = false
        }
    }

    /** Explicit terminal Enter, separate from all IME/editor actions. */
    fun submit(target: TerminalTarget?): SendResult {
        if (transmitting || draft.text.isNotEmpty()) return SendResult.DIRECT
        if (!connected(target)) return SendResult.DISCONNECTED
        return try {
            target!!.write("\r")
            SendResult.SENT
        } catch (_: Exception) {
            SendResult.WRITE_FAILED
        }
    }

    private fun rewrite(text: String): Rewrite {
        val active = transform ?: return Rewrite.Text(text)
        return try {
            active.rewrite(text)
        } catch (_: Exception) {
            // Never log or surface the exception: it may quote the draft.
            Rewrite.Refused("Translation failed")
        }
    }

    private fun connected(target: TerminalTarget?): Boolean =
        target != null && target.running && target.identity == sessionIdentity

    private fun validate(text: String): SendResult? {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c.isHighSurrogate()) {
                if (i + 1 >= text.length || !text[i + 1].isLowSurrogate())
                    return SendResult.INVALID_UNICODE
                i += 2
                continue
            }
            if (c.isLowSurrogate()) return SendResult.INVALID_UNICODE
            if ((c < ' ' && c != '\n' && c != '\t') || c in '\u007f'..'\u009f')
                return SendResult.UNSAFE_CONTROL
            i++
        }
        return null
    }
}
