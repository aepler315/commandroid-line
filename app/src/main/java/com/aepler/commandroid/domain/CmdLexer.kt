// SPDX-License-Identifier: GPL-3.0-only
package com.aepler.commandroid.domain

/**
 * Quote-aware tokenizer for one Windows CMD command line.
 *
 * CMD semantics: double quotes group text (no backslash escaping), `^` escapes the next character
 * outside quotes, `%NAME%` references an environment variable and `%%` is a literal percent sign.
 * `|`, `||`, `&&` and `&` separate commands; `>`, `>>`, `<` (optionally prefixed by a file
 * descriptor digit and followed by `&N`) are redirections.
 */
internal sealed interface Piece {
    val quoted: Boolean

    /** Literal text; [quoted] is true when it came from a quoted or escaped region. */
    data class Lit(val text: String, override val quoted: Boolean) : Piece

    /** `%NAME%` environment variable reference. */
    data class Var(val name: String, override val quoted: Boolean) : Piece
}

internal class CmdWord(
    val pieces: List<Piece>,
    val start: Int,
    val end: Int,
    /** True when the word used CMD-only escapes (`^x` or `%%`). */
    val hadEscape: Boolean = false
) {
    val anyQuoted: Boolean get() = pieces.any { it.quoted }
    val hasVars: Boolean get() = pieces.any { it is Piece.Var }

    /** Concatenated literal text, or null when the word references variables. */
    val literalText: String?
        get() = if (hasVars) null else pieces.joinToString("") { (it as Piece.Lit).text }

    /** Text with variables shown in CMD form; used for diagnostics and heuristics only. */
    val displayText: String
        get() = pieces.joinToString("") {
            when (it) {
                is Piece.Lit -> it.text
                is Piece.Var -> "%${it.name}%"
            }
        }

    /** Unquoted, unescaped, variable-free text (a candidate for a flag such as `/s`). */
    val bareText: String?
        get() = if (anyQuoted || hasVars || hadEscape) null else literalText

    fun withPieces(newPieces: List<Piece>): CmdWord = CmdWord(newPieces, start, end, hadEscape)
}

internal enum class ChainOperator(val posix: String) {
    PIPE(" | "),
    AND(" && "),
    OR(" || "),
    SEQUENCE("; ")
}

internal sealed interface CmdToken {
    val start: Int
    val end: Int

    class Word(val word: CmdWord) : CmdToken {
        override val start: Int get() = word.start
        override val end: Int get() = word.end
    }

    class Operator(val kind: ChainOperator, override val start: Int, override val end: Int) : CmdToken

    class Redirection(
        val fd: String,
        val op: String,
        /** `&1`-style duplication target; when null the next word is the file target. */
        val duplicate: String?,
        override val start: Int,
        override val end: Int
    ) : CmdToken
}

internal class CmdRedirect(val token: CmdToken.Redirection, val target: CmdWord?)

internal class CmdSegment(
    val words: List<CmdWord>,
    val redirects: List<CmdRedirect>,
    /** Source range in the original line; -1 for an empty segment. */
    val start: Int,
    val end: Int
) {
    val isEmpty: Boolean get() = words.isEmpty() && redirects.isEmpty()
}

internal class CmdChain(val segments: List<CmdSegment>, val operators: List<ChainOperator>)

internal object CmdLexer {
    private val VAR_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")

    fun tokenize(line: String): List<CmdToken> {
        val tokens = mutableListOf<CmdToken>()
        val pieces = mutableListOf<Piece>()
        val buf = StringBuilder()
        var bufQuoted = false
        var inQuotes = false
        var wordStart = -1
        var hadEscape = false

        fun flushBuf() {
            if (buf.isNotEmpty()) {
                pieces += Piece.Lit(buf.toString(), bufQuoted)
                buf.setLength(0)
            }
        }

        fun appendChar(c: Char, quoted: Boolean) {
            if (buf.isNotEmpty() && bufQuoted != quoted) flushBuf()
            bufQuoted = quoted
            buf.append(c)
        }

        fun startWord(i: Int) {
            if (wordStart < 0) wordStart = i
        }

        fun endWord(i: Int) {
            if (wordStart < 0) return
            flushBuf()
            if (pieces.isEmpty()) pieces += Piece.Lit("", true)
            tokens += CmdToken.Word(CmdWord(pieces.toList(), wordStart, i, hadEscape))
            pieces.clear()
            wordStart = -1
            hadEscape = false
        }

        fun percent(i: Int, quoted: Boolean): Int {
            if (i + 1 < line.length && line[i + 1] == '%') {
                appendChar('%', quoted)
                hadEscape = true
                return i + 2
            }
            val close = line.indexOf('%', i + 1)
            if (close > i + 1) {
                val name = line.substring(i + 1, close)
                if (VAR_NAME.matches(name)) {
                    flushBuf()
                    pieces += Piece.Var(name, quoted)
                    return close + 1
                }
            }
            appendChar('%', quoted)
            return i + 1
        }

        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (inQuotes) {
                when (c) {
                    '"' -> {
                        inQuotes = false
                        i++
                    }
                    '%' -> i = percent(i, true)
                    else -> {
                        appendChar(c, true)
                        i++
                    }
                }
                continue
            }
            when (c) {
                ' ', '\t' -> {
                    endWord(i)
                    i++
                }
                '"' -> {
                    startWord(i)
                    // Force an (possibly empty) quoted piece so `""` survives as an argument.
                    flushBuf()
                    bufQuoted = true
                    inQuotes = true
                    i++
                }
                '^' -> {
                    startWord(i)
                    hadEscape = true
                    if (i + 1 < line.length) appendChar(line[i + 1], true)
                    i += 2
                }
                '%' -> {
                    startWord(i)
                    i = percent(i, false)
                }
                '|' -> {
                    endWord(i)
                    if (i + 1 < line.length && line[i + 1] == '|') {
                        tokens += CmdToken.Operator(ChainOperator.OR, i, i + 2)
                        i += 2
                    } else {
                        tokens += CmdToken.Operator(ChainOperator.PIPE, i, i + 1)
                        i++
                    }
                }
                '&' -> {
                    endWord(i)
                    if (i + 1 < line.length && line[i + 1] == '&') {
                        tokens += CmdToken.Operator(ChainOperator.AND, i, i + 2)
                        i += 2
                    } else {
                        tokens += CmdToken.Operator(ChainOperator.SEQUENCE, i, i + 1)
                        i++
                    }
                }
                '>', '<' -> {
                    var fd = ""
                    var opStart = i
                    val fdPrefix = wordStart == i - 1 && pieces.isEmpty() && !bufQuoted &&
                        buf.length == 1 && buf[0] in "012"
                    if (fdPrefix) {
                        fd = buf.toString()
                        buf.setLength(0)
                        opStart = wordStart
                        wordStart = -1
                        hadEscape = false
                    } else {
                        endWord(i)
                    }
                    var op = c.toString()
                    var j = i + 1
                    if (c == '>' && j < line.length && line[j] == '>') {
                        op = ">>"
                        j++
                    }
                    var duplicate: String? = null
                    if (j + 1 < line.length && line[j] == '&' && line[j + 1].isDigit()) {
                        duplicate = "&" + line[j + 1]
                        j += 2
                    }
                    tokens += CmdToken.Redirection(fd, op, duplicate, opStart, j)
                    i = j
                }
                else -> {
                    startWord(i)
                    appendChar(c, false)
                    i++
                }
            }
        }
        endWord(line.length)
        return tokens
    }

    fun parse(line: String): CmdChain {
        val segments = mutableListOf<CmdSegment>()
        val operators = mutableListOf<ChainOperator>()
        var words = mutableListOf<CmdWord>()
        var redirects = mutableListOf<CmdRedirect>()
        var start = -1
        var end = -1
        var pendingRedirect: CmdToken.Redirection? = null

        fun closePending() {
            pendingRedirect?.let { redirects += CmdRedirect(it, null) }
            pendingRedirect = null
        }

        fun closeSegment() {
            closePending()
            segments += CmdSegment(words, redirects, start, end)
            words = mutableListOf()
            redirects = mutableListOf()
            start = -1
            end = -1
        }

        for (token in tokenize(line)) {
            if (token is CmdToken.Operator) {
                closeSegment()
                operators += token.kind
                continue
            }
            if (start < 0) start = token.start
            end = token.end
            when (token) {
                is CmdToken.Word -> {
                    val pending = pendingRedirect
                    if (pending != null) {
                        redirects += CmdRedirect(pending, token.word)
                        pendingRedirect = null
                    } else {
                        words += token.word
                    }
                }
                is CmdToken.Redirection -> {
                    closePending()
                    if (token.duplicate != null) {
                        redirects += CmdRedirect(token, null)
                    } else {
                        pendingRedirect = token
                    }
                }
                is CmdToken.Operator -> Unit
            }
        }
        closeSegment()
        return CmdChain(segments, operators)
    }
}
