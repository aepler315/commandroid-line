// SPDX-License-Identifier: GPL-3.0-only
package com.aepler.commandroid.ui.neoterm

import android.content.Context
import android.util.AttributeSet
import android.view.View
import com.aepler.commandroid.presentation.TerminalRenderedLine

/**
 * STUB contract: Canvas-rendered terminal viewport, completed by the view workstream.
 * Public API below is frozen; implementations may add members but must not change these.
 */
class NeoTermView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    /** Receives taps, InputConnection creation and key routing. */
    var inputController: NeoTermInputController? = null

    /** Invoked with (columns, rows) whenever the character grid geometry changes. */
    var onGridResized: ((columns: Int, rows: Int) -> Unit)? = null

    /** Replaces the scrollback contents with [lines] (already filtered to the active session). */
    fun submitLines(lines: List<TerminalRenderedLine>) {}

    /** Updates the staged prompt overlay row: prompt prefix, staged text, active IME composing span. */
    fun setPromptState(prompt: String, stagedText: String, composingRange: IntRange?) {}

    /** Clears the scrollback ring buffer and resets the cursor to (0, 0). */
    fun clearScreen() {}
}
