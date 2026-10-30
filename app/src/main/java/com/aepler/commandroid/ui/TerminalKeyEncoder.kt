// SPDX-License-Identifier: GPL-3.0-only
package com.aepler.commandroid.ui

import com.aepler.commandroid.ui.neoterm.ModifierKey

/** Encodes terminal keys and CTRL/ALT combinations as the bytes a VT-style program expects on stdin. */
object TerminalKeyEncoder {
    private const val ESC: Byte = 0x1b

    /** Bytes for a non-modifier [key] of the key bar; ALT prefixes ESC. Null for CTRL/ALT themselves. */
    fun encodeKey(key: ModifierKey, alt: Boolean = false): ByteArray? {
        val base = when (key) {
            ModifierKey.CTRL, ModifierKey.ALT -> return null
            ModifierKey.TAB -> byteArrayOf('\t'.code.toByte())
            ModifierKey.ESC -> byteArrayOf(ESC)
            ModifierKey.UP -> csi('A')
            ModifierKey.DOWN -> csi('B')
            ModifierKey.RIGHT -> csi('C')
            ModifierKey.LEFT -> csi('D')
        }
        return if (alt) byteArrayOf(ESC) + base else base
    }

    /**
     * Control byte for CTRL+[char] (a-z, @, [, \, ], ^, _, space, ?), or null when the combination
     * has no ASCII control code.
     */
    fun controlByteFor(char: Char): Byte? = when (val c = char.lowercaseChar()) {
        in 'a'..'z' -> (c - 'a' + 1).toByte()
        '@', ' ', '2' -> 0
        '[', '3' -> 27
        '\\', '4' -> 28
        ']', '5' -> 29
        '^', '6' -> 30
        '_', '-', '7' -> 31
        '?', '8' -> 127
        else -> null
    }

    /**
     * Bytes for [codePoint] typed with the given modifiers: CTRL maps to a control byte, ALT prefixes
     * ESC. Null when CTRL is set but the character has no control code, or [codePoint] is invalid.
     */
    fun encodeChar(codePoint: Int, ctrl: Boolean, alt: Boolean): ByteArray? {
        // Dead-key values (COMBINING_ACCENT bit), out-of-range values and lone surrogates.
        if (codePoint <= 0 || !Character.isValidCodePoint(codePoint) ||
            codePoint in Character.MIN_SURROGATE.code..Character.MAX_SURROGATE.code
        ) return null
        val base = if (ctrl) {
            if (codePoint > 0xFFFF) return null
            val control = controlByteFor(codePoint.toChar()) ?: return null
            byteArrayOf(control)
        } else {
            String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8)
        }
        return if (alt) byteArrayOf(ESC) + base else base
    }

    /** True when CTRL+[codePoint] is the interrupt combination (CTRL+C). */
    fun isInterrupt(codePoint: Int, ctrl: Boolean): Boolean = ctrl && (codePoint == 'c'.code || codePoint == 'C'.code)

    private fun csi(final: Char): ByteArray = byteArrayOf(ESC, '['.code.toByte(), final.code.toByte())
}
