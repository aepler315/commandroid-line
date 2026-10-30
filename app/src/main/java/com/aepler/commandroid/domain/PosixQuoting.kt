// SPDX-License-Identifier: GPL-3.0-only
package com.aepler.commandroid.domain

/**
 * Renders tokenized CMD words as POSIX `sh` words. User-supplied text is never emitted unquoted
 * unless every character is shell-inert, so quoted arguments cannot inject shell syntax.
 */
internal object PosixQuoting {

    enum class VarStyle {
        /** `${NAME}` (word splitting allowed, e.g. echo). */
        BARE,

        /** `"${NAME}"`. */
        QUOTED,

        /** `"${NAME:?}"`: the shell aborts the command when NAME is unset/empty (destructive ops). */
        REQUIRED
    }

    private const val SAFE_PUNCTUATION = "_-./,:@%+="

    private fun isSafe(c: Char): Boolean =
        (c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9') || c in SAFE_PUNCTUATION

    fun singleQuote(text: String): String = "'" + text.replace("'", "'\\''") + "'"

    fun quoteIfNeeded(text: String): String =
        if (text.isNotEmpty() && text.all(::isSafe)) text else singleQuote(text)

    private fun varRef(name: String, style: VarStyle): String =
        if (style == VarStyle.REQUIRED) "\${$name:?}" else "\${$name}"

    /**
     * @param allowGlob emit `*` and `?` bare so the shell expands them (CMD wildcard semantics:
     *   commands such as dir and del expand wildcards inside quotes too, as in
     *   `dir "C:\My Docs\*.txt"`). Everything else, quoted or not, is single-quoted as needed;
     *   quoted variables stay double-quoted. Without it, quoted text never globs.
     */
    fun render(
        pieces: List<Piece>,
        style: VarStyle = VarStyle.QUOTED,
        allowGlob: Boolean = false
    ): String {
        if (pieces.any { it.quoted }) {
            val globs = allowGlob && pieces.any { it is Piece.Lit && (it.text.contains('*') || it.text.contains('?')) }
            if (!globs) return renderQuoted(pieces, style)
            if (pieces.none { it is Piece.Var }) {
                return renderUnquotedLiteral(pieces.joinToString("") { (it as Piece.Lit).text }, allowGlob = true)
                    .ifEmpty { "''" }
            }
        }
        val out = StringBuilder()
        for (piece in pieces) {
            when (piece) {
                is Piece.Var -> out.append(
                    if (style == VarStyle.BARE && !piece.quoted) varRef(piece.name, style)
                    else "\"" + varRef(piece.name, style) + "\""
                )
                is Piece.Lit -> out.append(renderUnquotedLiteral(piece.text, allowGlob))
            }
        }
        return if (out.isEmpty()) "''" else out.toString()
    }

    private fun renderUnquotedLiteral(text: String, allowGlob: Boolean): String {
        if (!allowGlob) return if (text.isEmpty()) "" else quoteIfNeeded(text)
        val out = StringBuilder()
        val run = StringBuilder()
        for (c in text) {
            if (c == '*' || c == '?') {
                if (run.isNotEmpty()) {
                    out.append(quoteIfNeeded(run.toString()))
                    run.setLength(0)
                }
                out.append(c)
            } else {
                run.append(c)
            }
        }
        if (run.isNotEmpty()) out.append(quoteIfNeeded(run.toString()))
        return out.toString()
    }

    private fun renderQuoted(pieces: List<Piece>, style: VarStyle): String {
        if (pieces.none { it is Piece.Var }) {
            return singleQuote(pieces.joinToString("") { (it as Piece.Lit).text })
        }
        val out = StringBuilder("\"")
        for (piece in pieces) {
            when (piece) {
                is Piece.Var -> out.append(varRef(piece.name, style))
                is Piece.Lit -> piece.text.forEach { c ->
                    if (c == '\\' || c == '"' || c == '$' || c == '`') out.append('\\')
                    out.append(c)
                }
            }
        }
        return out.append('"').toString()
    }
}
