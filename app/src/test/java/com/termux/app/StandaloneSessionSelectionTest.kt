// SPDX-License-Identifier: GPL-3.0-only
package com.termux.app

import android.app.Application
import com.termux.shared.models.ExecutionCommand
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Captures the service/session boundary before upstream default-shell probing or JNI. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class,
    shadows = [RecordingTermuxSessionShadow::class],
    instrumentedPackages = ["com.termux.shared.shell"])
class StandaloneSessionSelectionTest {
    @Before fun resetRecording() { RecordingTermuxSessionShadow.command = null }

    @Test fun normalAndFailsafeManualSessionsExplicitlySelectAndroidShell() {
        val service = Robolectric.buildService(TermuxService::class.java).get()
        service.mTerminalTranscriptRows = 100
        for (failsafe in listOf(false, true)) {
            service.createTermuxSession(null, null, null, null, failsafe, null)
            val command = requireNotNull(RecordingTermuxSessionShadow.command)
            assertEquals("Never probe vendor login/bash/zsh for a manual session", "/system/bin/sh", command.executable)
            assertEquals(failsafe, command.isFailsafe)
        }
    }

    @Test fun explicitlyRequestedCommandsKeepTheirExecutableAndArguments() {
        val service = Robolectric.buildService(TermuxService::class.java).get()
        service.mTerminalTranscriptRows = 100
        val arguments = arrayOf("-c", "printf explicit")
        service.createTermuxSession("/system/bin/custom-command", arguments, null, "/system", false, "custom")
        val manual = requireNotNull(RecordingTermuxSessionShadow.command)
        assertEquals("/system/bin/custom-command", manual.executable)
        assertArrayEquals(arguments, manual.arguments)
        assertEquals("/system", manual.workingDirectory)

        val plugin = ExecutionCommand(42, "/system/bin/plugin-command", arguments, null, "/system", false, false)
        plugin.isPluginExecutionCommand = true
        service.createTermuxSession(plugin, "plugin")
        assertSame(plugin, RecordingTermuxSessionShadow.command)
        assertEquals("/system/bin/plugin-command", plugin.executable)
        assertArrayEquals(arguments, plugin.arguments)
    }
}
