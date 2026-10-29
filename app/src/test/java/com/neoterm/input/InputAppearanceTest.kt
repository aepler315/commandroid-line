// SPDX-License-Identifier: GPL-3.0-only
package com.neoterm.input

import android.app.Application
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.termux.R
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The upstream light activity has a black window: inherited black labels disappear. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class InputAppearanceTest {
    @Test fun controlsRemainReadableInUpstreamLightActivity() = checkContrast(R.style.Theme_Termux)
    @Test fun controlsRemainReadableInUpstreamDarkActivity() = checkContrast(R.style.Theme_Termux_Black)

    private fun checkContrast(theme: Int) {
        val context = ContextThemeWrapper(RuntimeEnvironment.application, theme)
        val surface = LayoutInflater.from(context).inflate(R.layout.view_neoterm_input, null)
        // A running session can change the activity decor to its own palette background.
        for (terminalBackground in intArrayOf(Color.BLACK, Color.WHITE)) {
            val background = (surface.background as? ColorDrawable)?.color ?: terminalBackground
            for (id in intArrayOf(R.id.neoterm_runtime_notice, R.id.neoterm_mode_auto,
                R.id.neoterm_mode_text, R.id.neoterm_mode_terminal, R.id.neoterm_draft_status,
                R.id.neoterm_draft)) {
                val text = surface.findViewById<TextView>(id)
                assertReadable(text.currentTextColor, background, context, id)
                if (id == R.id.neoterm_draft)
                    assertReadable(text.currentHintTextColor, background, context, id)
            }
        }
    }

    private fun assertReadable(foreground: Int, background: Int, context: Context, id: Int) {
        val contrast = ColorUtils.calculateContrast(foreground, background)
        assertTrue("${context.resources.getResourceEntryName(id)} contrast $contrast is below 4.5:1", contrast >= 4.5)
    }
}
