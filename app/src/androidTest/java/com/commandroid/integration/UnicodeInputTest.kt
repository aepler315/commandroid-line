// SPDX-License-Identifier: GPL-3.0-only
package com.commandroid.integration

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.termux.R
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Unicode from the IME draft to a program's stdin, byte for byte: a precomposed letter, CJK
 * (double width), a supplementary-plane emoji (surrogate pair in Java), a combining sequence and a
 * ZWJ emoji sequence. The receiver hex-dumps exactly what `read` got.
 */
@RunWith(AndroidJUnit4::class)
class UnicodeInputTest {

    @Test fun transmittedUnicodeArrivesByteExactAndIsShownByTheEmulator() {
        val precomposed = "caf\u00e9"
        val cjk = "\u4e2d\u6587"
        val emoji = "\ud83d\ude42"
        val combining = "e\u0301"
        val zwj = "\ud83d\udc69\u200d\ud83d\udcbb"
        val text = "$precomposed $cjk $emoji $combining $zwj"

        PtyHarness.launch().use { h ->
            val shell = h.openSession("itest unicode")
            val nonce = newNonce()
            h.run(shell, "${printMarker("UREADY", nonce)}; IFS= read -r ux; " +
                printValue("UHEX", nonce, "\"\$(printf '%s' \"\$ux\" | $HEX_PIPE)\""))
            h.awaitMarker(shell, "UREADY", nonce)

            h.selectMode(R.id.neoterm_mode_text)
            h.commitDraft(text)
            h.click(R.id.neoterm_transmit)
            assertTrue("Transmit must clear the handed-off draft", h.draft().isEmpty())
            assertFor("Transmit completed the read without Enter") { h.valueOrNull(shell, "UHEX", nonce) == null }
            h.click(R.id.neoterm_enter)

            val hex = h.awaitValue(shell, "UHEX", nonce, message = "Receiver did not report its input")
            assertTrue("Program stdin did not receive the exact UTF-8 bytes", hex == utf8Hex(text))

            // The tty's canonical echo puts the text on its own line from column 0, narrower than any
            // phone terminal, so no wide character is split by a line wrap.
            assertTrue("Emulator transcript did not show the text intact", h.transcript(shell).contains(text))
        }
    }
}
