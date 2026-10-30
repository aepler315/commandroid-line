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

    private fun onControlKey(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_C && event.isCtrlPressed) {
            val active = viewModel.state.value.activeSessionId
            if (active.isNotEmpty()) viewModel.dispatch(TerminalIntent.KillActiveProcess(active))
            return true
        }
        return false
    }

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
}
