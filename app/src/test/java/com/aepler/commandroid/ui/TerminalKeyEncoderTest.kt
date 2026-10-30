// SPDX-License-Identifier: GPL-3.0-only
package com.aepler.commandroid.ui

import com.aepler.commandroid.ui.neoterm.ModifierKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalKeyEncoderTest {

    private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8)

    @Test
    fun barKeysMapToVtSequences() {
        assertArrayEquals(bytes("\t"), TerminalKeyEncoder.encodeKey(ModifierKey.TAB))
        assertArrayEquals(bytes("\u001b"), TerminalKeyEncoder.encodeKey(ModifierKey.ESC))
        assertArrayEquals(bytes("\u001b[A"), TerminalKeyEncoder.encodeKey(ModifierKey.UP))
        assertArrayEquals(bytes("\u001b[B"), TerminalKeyEncoder.encodeKey(ModifierKey.DOWN))
        assertArrayEquals(bytes("\u001b[C"), TerminalKeyEncoder.encodeKey(ModifierKey.RIGHT))
        assertArrayEquals(bytes("\u001b[D"), TerminalKeyEncoder.encodeKey(ModifierKey.LEFT))
        assertArrayEquals(bytes("\u001b\u001b[D"), TerminalKeyEncoder.encodeKey(ModifierKey.LEFT, alt = true))
        assertNull(TerminalKeyEncoder.encodeKey(ModifierKey.CTRL))
        assertNull(TerminalKeyEncoder.encodeKey(ModifierKey.ALT))
    }

    @Test
    fun ctrlLettersMapToControlBytes() {
        assertEquals(1.toByte(), TerminalKeyEncoder.controlByteFor('a'))
        assertEquals(4.toByte(), TerminalKeyEncoder.controlByteFor('D'))
        assertEquals(26.toByte(), TerminalKeyEncoder.controlByteFor('z'))
        assertEquals(27.toByte(), TerminalKeyEncoder.controlByteFor('['))
        assertEquals(127.toByte(), TerminalKeyEncoder.controlByteFor('?'))
        assertNull(TerminalKeyEncoder.controlByteFor('!'))
    }

    @Test
    fun encodeCharAppliesModifiers() {
        assertArrayEquals(byteArrayOf(4), TerminalKeyEncoder.encodeChar('d'.code, ctrl = true, alt = false))
        assertArrayEquals(bytes("\u001bx"), TerminalKeyEncoder.encodeChar('x'.code, ctrl = false, alt = true))
        assertArrayEquals(bytes("\u001b\u0001"), TerminalKeyEncoder.encodeChar('a'.code, ctrl = true, alt = true))
        assertArrayEquals(bytes("🚀"), TerminalKeyEncoder.encodeChar(0x1F680, ctrl = false, alt = false))
        assertNull(TerminalKeyEncoder.encodeChar(0x1F680, ctrl = true, alt = false))
    }

    @Test
    fun ctrlCIsInterrupt() {
        assertTrue(TerminalKeyEncoder.isInterrupt('c'.code, ctrl = true))
        assertTrue(TerminalKeyEncoder.isInterrupt('C'.code, ctrl = true))
        assertFalse(TerminalKeyEncoder.isInterrupt('c'.code, ctrl = false))
        assertFalse(TerminalKeyEncoder.isInterrupt('d'.code, ctrl = true))
    }
}
