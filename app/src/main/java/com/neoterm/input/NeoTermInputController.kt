// SPDX-License-Identifier: GPL-3.0-only
package com.neoterm.input

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.RadioGroup
import android.widget.TextView
import com.commandroid.runtime.FlavorRuntime
import com.termux.R
import com.termux.app.TermuxActivity
import com.termux.terminal.TerminalSession
import java.io.File

/** Activity-scoped; no draft persistence, terminal-screen heuristics, or logging. */
class NeoTermInputController(private val activity: TermuxActivity) {
    private val editor = activity.findViewById<CompositionEditText>(R.id.neoterm_draft)
    private val panel = activity.findViewById<View>(R.id.neoterm_draft_panel)
    private val status = activity.findViewById<TextView>(R.id.neoterm_draft_status)
    private val modes = activity.findViewById<RadioGroup>(R.id.neoterm_modes)
    private val compose = activity.findViewById<Button>(R.id.neoterm_compose)
    private val cmd = activity.findViewById<CheckBox>(R.id.neoterm_cmd)
    private val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    init {
        editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                status.setText(R.string.neoterm_draft_label)
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        modes.setOnCheckedChangeListener { _, id ->
            editor.engine.setMode(when (id) {
                R.id.neoterm_mode_text -> InputMode.TEXT
                R.id.neoterm_mode_terminal -> InputMode.TERMINAL
                else -> InputMode.AUTO
            })
            render(true)
        }
        // Physical-keyboard navigation may focus an already-selected mode button.
        // Re-selecting it must return typing to the input view even without a mode change.
        for (id in intArrayOf(R.id.neoterm_mode_auto, R.id.neoterm_mode_text, R.id.neoterm_mode_terminal)) {
            activity.findViewById<View>(id).setOnClickListener { inputView().requestFocus() }
        }
        compose.setOnClickListener { editor.engine.openComposer(); render(true) }
        activity.findViewById<TextView>(R.id.neoterm_runtime_notice).setText(FlavorRuntime.runtimeNotice())
        // Commandroid CMD translation is a Transmit rewrite into the PTY session; it never runs commands.
        cmd.setOnCheckedChangeListener { _, checked ->
            editor.engine.transform = if (checked) cmdTransform() else null
            if (checked) {
                editor.engine.openComposer()
                status.setText(R.string.commandroid_cmd_on)
            } else {
                status.setText(R.string.neoterm_draft_label)
            }
            render(true)
        }
        activity.findViewById<View>(R.id.neoterm_keys).setOnClickListener {
            activity.toggleTerminalToolbar()
            inputView().requestFocus()
        }
        activity.findViewById<View>(R.id.neoterm_transmit).setOnClickListener {
            showResult(editor.engine.transmit(target()))
            editor.requestFocus()
            imm.restartInput(editor)
        }
        activity.findViewById<View>(R.id.neoterm_clear).setOnClickListener {
            editor.engine.discardDraft()
            status.setText(R.string.neoterm_draft_label)
            editor.requestFocus()
            imm.restartInput(editor)
        }
        activity.findViewById<View>(R.id.neoterm_enter).setOnClickListener {
            showResult(editor.engine.submit(target()))
            inputView().requestFocus()
        }
        render(false)
    }

    fun onSessionChanged(session: TerminalSession?) {
        editor.engine.bindSession(session?.mHandle)
        status.setText(R.string.neoterm_draft_label)
        render(false)
        imm.restartInput(editor)
    }

    fun inputView(): View = if (editor.engine.isComposing) editor else activity.terminalView

    fun isComposerFocused(): Boolean = editor.hasFocus() && editor.engine.isComposing

    private fun cmdTransform() = CmdTransmitTransform(
        workingDirectory = {
            val session = activity.currentSession
            File(session?.cwd ?: FlavorRuntime.shellEnvironment(activity).defaultWorkingDirectoryPath)
        },
        environment = {
            val session = activity.currentSession
            val env = FlavorRuntime.shellEnvironment(activity)
            CmdTransmitTransform.parseEnvironment(
                env.buildEnvironment(activity, false, session?.cwd ?: env.defaultWorkingDirectoryPath))
        },
    )

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
        if (result == SendResult.REFUSED) {
            status.text = activity.getString(R.string.commandroid_cmd_refused, editor.engine.lastRefusal ?: "")
            return
        }
        status.setText(when (result) {
            SendResult.SENT -> R.string.neoterm_transmitted
            SendResult.EMPTY -> R.string.neoterm_empty
            SendResult.DISCONNECTED -> R.string.neoterm_disconnected
            SendResult.BRACKETED_PASTE_REQUIRED -> R.string.neoterm_bracketed_required
            SendResult.UNSAFE_CONTROL -> R.string.neoterm_unsafe_control
            SendResult.INVALID_UNICODE -> R.string.neoterm_invalid_unicode
            SendResult.WRITE_FAILED -> R.string.neoterm_write_failed
            SendResult.DIRECT -> R.string.neoterm_unsent_draft
            SendResult.REFUSED -> R.string.neoterm_draft_label
        })
    }
}
