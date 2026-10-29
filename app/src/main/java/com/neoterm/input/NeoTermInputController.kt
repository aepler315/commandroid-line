// SPDX-License-Identifier: GPL-3.0-only
package com.neoterm.input

import android.content.Context
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.RadioGroup
import android.widget.TextView
import com.termux.R
import com.termux.app.TermuxActivity
import com.termux.terminal.TerminalSession

/** Activity-scoped; no draft persistence, terminal-screen heuristics, or logging. */
class NeoTermInputController(private val activity: TermuxActivity) {
    private val editor = activity.findViewById<CompositionEditText>(R.id.neoterm_draft)
    private val panel = activity.findViewById<View>(R.id.neoterm_draft_panel)
    private val status = activity.findViewById<TextView>(R.id.neoterm_draft_status)
    private val modes = activity.findViewById<RadioGroup>(R.id.neoterm_modes)
    private val compose = activity.findViewById<Button>(R.id.neoterm_compose)
    private val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    init {
        modes.setOnCheckedChangeListener { _, id ->
            editor.engine.setMode(when (id) {
                R.id.neoterm_mode_text -> InputMode.TEXT
                R.id.neoterm_mode_terminal -> InputMode.TERMINAL
                else -> InputMode.AUTO
            })
            render(true)
        }
        compose.setOnClickListener { editor.engine.openComposer(); render(true) }
        activity.findViewById<View>(R.id.neoterm_keys).setOnClickListener {
            activity.toggleTerminalToolbar()
        }
        activity.findViewById<View>(R.id.neoterm_transmit).setOnClickListener {
            showResult(editor.engine.transmit(target()))
            imm.restartInput(editor)
        }
        activity.findViewById<View>(R.id.neoterm_clear).setOnClickListener {
            editor.engine.discardDraft()
            status.setText(R.string.neoterm_draft_label)
            imm.restartInput(editor)
        }
        activity.findViewById<View>(R.id.neoterm_enter).setOnClickListener {
            showResult(editor.engine.submit(target()))
        }
        render(false)
    }

    fun onSessionChanged(session: TerminalSession?) {
        editor.engine.bindSession(session?.mHandle)
        status.setText(R.string.neoterm_draft_label)
        render(false)
        imm.restartInput(editor)
    }

    fun isComposerFocused(): Boolean = editor.hasFocus() && editor.engine.isComposing

    private fun target(): TerminalTarget? {
        val session = activity.currentSession ?: return null
        return object : TerminalTarget {
            override val identity: String get() = session.mHandle
            override val running: Boolean get() = session.isRunning && session.emulator != null
            override val bracketedPaste: Boolean get() = session.emulator?.isBracketedPasteModeEnabled ?: false
            override fun write(text: String) { session.write(text) }
        }
    }

    private fun render(focus: Boolean) {
        val composing = editor.engine.isComposing
        activity.terminalView.setInputConnectionEnabled(!composing)
        panel.visibility = if (composing) View.VISIBLE else View.GONE
        compose.visibility = if (editor.engine.mode == InputMode.AUTO && !composing) View.VISIBLE else View.GONE
        if (composing) {
            if (focus) {
                editor.requestFocus()
                imm.restartInput(editor)
                imm.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
            }
        } else if (focus || editor.hasFocus()) {
            activity.terminalView.requestFocus()
            imm.restartInput(activity.terminalView)
        }
    }

    private fun showResult(result: SendResult) {
        status.setText(when (result) {
            SendResult.SENT -> R.string.neoterm_transmitted
            SendResult.EMPTY -> R.string.neoterm_empty
            SendResult.DISCONNECTED -> R.string.neoterm_disconnected
            SendResult.BRACKETED_PASTE_REQUIRED -> R.string.neoterm_bracketed_required
            SendResult.UNSAFE_CONTROL -> R.string.neoterm_unsafe_control
            SendResult.INVALID_UNICODE -> R.string.neoterm_invalid_unicode
            SendResult.WRITE_FAILED -> R.string.neoterm_write_failed
            SendResult.DIRECT -> R.string.neoterm_unsent_draft
        })
    }
}
