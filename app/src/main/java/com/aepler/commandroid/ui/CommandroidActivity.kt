// SPDX-License-Identifier: GPL-3.0-only
package com.aepler.commandroid.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.aepler.commandroid.data.process.TerminalSessionService
import com.aepler.commandroid.presentation.TerminalIntent
import com.aepler.commandroid.presentation.TerminalSideEffect
import com.aepler.commandroid.presentation.TerminalViewModel
import com.aepler.commandroid.presentation.TerminalViewState
import com.aepler.commandroid.ui.neoterm.ModifierKey
import com.aepler.commandroid.ui.neoterm.ModifierKeyBar
import com.aepler.commandroid.ui.neoterm.NeoTermInputController
import com.aepler.commandroid.ui.neoterm.NeoTermView
import com.termux.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/** Hosts the Canvas terminal and wires it to [TerminalViewModel]; shells live in [TerminalSessionService]. */
@AndroidEntryPoint
class CommandroidActivity : ComponentActivity() {

    private val viewModel: TerminalViewModel by viewModels()

    private lateinit var terminalView: NeoTermView
    private lateinit var inputController: NeoTermInputController
    private lateinit var sessionTitle: TextView
    private lateinit var modifierBar: ModifierKeyBar

    /** Session the IME was last restarted for; detects switches the side-effect stream did not deliver. */
    private var imeSessionId: String? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {}
        override fun onServiceDisconnected(name: ComponentName?) {}
    }

    private val notificationPermissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* Optional; sessions run either way. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_commandroid)

        terminalView = findViewById(R.id.commandroid_terminal)
        sessionTitle = findViewById(R.id.commandroid_session_title)

        inputController = NeoTermInputController(
            inputViewProvider = { terminalView },
            onStagedBufferChanged = { stagedText, composingRange ->
                viewModel.dispatch(TerminalIntent.UpdateStagedInput(stagedText, composingRange))
            },
            onCommitLine = { command -> viewModel.dispatch(TerminalIntent.SubmitCommand(command)) },
            onControlKeyIntercepted = ::onControlKey
        )
        terminalView.inputController = inputController
        inputController.onCommitTextIntercepted = ::onCommitTextWithModifiers
        // Hardware keyboards deliver key events to the focused view, not through the InputConnection.
        terminalView.setOnKeyListener { _, _, event -> inputController.handleKeyEvent(event) }

        modifierBar = ModifierKeyBar(this).apply { listener = ModifierKeyBar.Listener(::onModifierKey) }
        findViewById<ViewGroup>(R.id.commandroid_modifier_bar_container).addView(
            modifierBar,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        terminalView.onGridResized = { columns, rows -> viewModel.dispatch(TerminalIntent.ResizeTerminal(columns, rows)) }

        findViewById<TextView>(R.id.commandroid_new_session).setOnClickListener {
            viewModel.dispatch(TerminalIntent.CreateSession)
        }
        findViewById<TextView>(R.id.commandroid_next_session).setOnClickListener {
            viewModel.switchToNextSession()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect(::render) }
                launch { viewModel.sideEffects.collect(::handleSideEffect) }
            }
        }

        startSessionService()
        if (savedInstanceState == null) requestNotificationPermissionIfNeeded()
    }

    override fun onStart() {
        super.onStart()
        serviceBound = runCatching {
            bindService(Intent(this, TerminalSessionService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
    }

    override fun onStop() {
        if (serviceBound) {
            runCatching { unbindService(serviceConnection) }
            serviceBound = false
        }
        super.onStop()
    }

    private fun render(state: TerminalViewState) {
        val active = state.activeSession ?: return
        if (active.sessionId != imeSessionId) restartIme(active.sessionId, active.stagedInput)
        terminalView.submitLines(state.terminalLines)
        terminalView.setPromptState("${active.windowsPromptDisplay} ", active.stagedInput, active.composingRange)
        val index = state.sessions.indexOf(active)
        sessionTitle.text = getString(R.string.commandroid_session_title, active.title, index + 1, state.sessions.size)
    }

    private fun handleSideEffect(effect: TerminalSideEffect) {
        when (effect) {
            is TerminalSideEffect.RestartImeForSession -> restartIme(effect.sessionId, effect.restoredStagedText)
            is TerminalSideEffect.HapticFeedback -> {
                val constant = when {
                    !effect.isError -> HapticFeedbackConstants.KEYBOARD_TAP
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> HapticFeedbackConstants.REJECT
                    else -> HapticFeedbackConstants.LONG_PRESS
                }
                terminalView.performHapticFeedback(constant)
            }
        }
    }

    private fun restartIme(sessionId: String, restoredStagedText: String) {
        imeSessionId = sessionId
        // The controller ignores a repeated id, so state and side-effect paths may both call this.
        inputController.onSessionChanged(sessionId, restoredStagedText)
    }

    /** Key events from the IME or a hardware keyboard, seen before the staged buffer handles them. */
    private fun onControlKey(keyCode: Int, event: KeyEvent): Boolean {
        if (isModifierKeyCode(keyCode)) return false
        val armed = modifierBar.isCtrlActive || modifierBar.isAltActive
        if (!event.isCtrlPressed && !event.isAltPressed && !armed) return false
        val codePoint = event.getUnicodeChar(0)
        if (codePoint == 0) {
            // Not a character: let bar modifiers apply to the matching terminal key, if any.
            val key = KEY_FOR_KEYCODE[keyCode] ?: return false
            val (ctrl, alt) = consumeModifiers(event)
            onModifierKey(key, ctrl, alt)
            return true
        }
        val (ctrl, alt) = consumeModifiers(event)
        sendModifiedChar(codePoint, ctrl, alt)
        return true
    }

    /** IME text committed while a sticky CTRL/ALT is armed is sent as a key combination instead. */
    private fun onCommitTextWithModifiers(text: CharSequence): Boolean {
        if (!modifierBar.isCtrlActive && !modifierBar.isAltActive) return false
        if (Character.codePointCount(text, 0, text.length) != 1) return false
        val armed = modifierBar.consumeModifiers()
        sendModifiedChar(Character.codePointAt(text, 0), ctrl = armed[0], alt = armed[1])
        return true
    }

    private fun consumeModifiers(event: KeyEvent): Pair<Boolean, Boolean> {
        val armed = modifierBar.consumeModifiers()
        return (event.isCtrlPressed || armed[0]) to (event.isAltPressed || armed[1])
    }

    private fun sendModifiedChar(codePoint: Int, ctrl: Boolean, alt: Boolean) {
        val active = viewModel.state.value.activeSession ?: return
        if (TerminalKeyEncoder.isInterrupt(codePoint, ctrl)) {
            viewModel.dispatch(TerminalIntent.KillActiveProcess(active.sessionId))
            return
        }
        if (!active.isProcessRunning) {
            when {
                ctrl && (codePoint == 'l'.code || codePoint == 'L'.code) -> viewModel.dispatch(TerminalIntent.ClearScreen)
                ctrl && (codePoint == 'u'.code || codePoint == 'U'.code) -> inputController.clearStagedText()
                !ctrl -> inputController.insertText(String(Character.toChars(codePoint)))
                else -> terminalView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            }
            return
        }
        val bytes = TerminalKeyEncoder.encodeChar(codePoint, ctrl, alt) ?: return
        viewModel.dispatch(TerminalIntent.SendRawInput(active.sessionId, bytes))
    }

    /** Taps on the key bar; CTRL/ALT only arm themselves and are applied to the next key. */
    private fun onModifierKey(key: ModifierKey, ctrl: Boolean, alt: Boolean) {
        if (key.isModifier) return
        val active = viewModel.state.value.activeSession ?: return
        if (active.isProcessRunning) {
            val bytes = TerminalKeyEncoder.encodeKey(key, alt) ?: return
            viewModel.dispatch(TerminalIntent.SendRawInput(active.sessionId, bytes))
            return
        }
        // No foreground command: the keys edit the staged line.
        when (key) {
            ModifierKey.LEFT -> inputController.moveCursor(-1)
            ModifierKey.RIGHT -> inputController.moveCursor(1)
            ModifierKey.ESC -> inputController.clearStagedText()
            ModifierKey.TAB -> inputController.insertText("\t")
            else -> Unit // UP/DOWN: no command history yet.
        }
    }

    private fun isModifierKeyCode(keyCode: Int): Boolean =
        KeyEvent.isModifierKey(keyCode)

    private fun startSessionService() {
        // Plain start while in the foreground; the service promotes itself with startForeground().
        // startForegroundService() would crash the app if the service ever failed to promote in time.
        runCatching { startService(Intent(this, TerminalSessionService::class.java)) }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted && !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
            notificationPermissionRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private companion object {
        val KEY_FOR_KEYCODE: Map<Int, ModifierKey> = mapOf(
            KeyEvent.KEYCODE_TAB to ModifierKey.TAB,
            KeyEvent.KEYCODE_ESCAPE to ModifierKey.ESC,
            KeyEvent.KEYCODE_DPAD_UP to ModifierKey.UP,
            KeyEvent.KEYCODE_DPAD_DOWN to ModifierKey.DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT to ModifierKey.LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT to ModifierKey.RIGHT
        )
    }
}
