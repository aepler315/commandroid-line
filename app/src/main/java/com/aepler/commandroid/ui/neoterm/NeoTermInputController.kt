// SPDX-License-Identifier: GPL-3.0-only
package com.aepler.commandroid.ui.neoterm

import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

/** STUB contract: staged IME input controller, completed by the input workstream. */
class NeoTermInputController(
    private val inputViewProvider: () -> View,
    private val onStagedBufferChanged: (stagedText: String, composingRange: IntRange?) -> Unit,
    private val onCommitLine: (committedCommand: String) -> Unit,
    private val onControlKeyIntercepted: (keyCode: Int, event: KeyEvent) -> Boolean
) {
    fun inputView(): View = inputViewProvider()

    fun onSessionChanged(newSessionId: String, restoredStagedText: String = "") {}

    fun onTerminalTap() {}

    fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection = BaseInputConnection(inputView(), true)
}
