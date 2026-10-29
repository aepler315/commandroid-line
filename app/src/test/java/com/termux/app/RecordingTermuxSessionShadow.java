// SPDX-License-Identifier: GPL-3.0-only
package com.termux.app;

import android.content.Context;
import com.termux.shared.models.ExecutionCommand;
import com.termux.shared.shell.ShellEnvironmentClient;
import com.termux.shared.shell.TermuxSession;
import com.termux.terminal.TerminalSessionClient;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/** Records execution requests without native PTY creation or changing shell-selection inputs. */
@Implements(value = TermuxSession.class, isInAndroidSdk = false)
public class RecordingTermuxSessionShadow {
    public static ExecutionCommand command;

    @Implementation
    protected static TermuxSession execute(Context context, ExecutionCommand executionCommand,
            TerminalSessionClient terminalSessionClient, TermuxSession.TermuxSessionClient termuxSessionClient,
            ShellEnvironmentClient environment, String sessionName, boolean setStdoutOnExit) {
        command = executionCommand;
        return null;
    }
}
