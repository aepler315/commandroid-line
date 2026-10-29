// SPDX-License-Identifier: GPL-3.0-only
package com.termux.terminal;

import com.termux.shared.terminal.TermuxTerminalSessionClientBase;
import java.nio.charset.StandardCharsets;

/** Test-only real queue/emulator fixture with no spawned process or JNI calls. */
public final class SessionFixture {
    public static TerminalSession create() {
        TerminalSession session = new TerminalSession("/unused", "/unused", new String[0], new String[0],
            null, new TermuxTerminalSessionClientBase());
        session.mEmulator = new TerminalEmulator(session, 80, 24, 10, 15, null,
            new TermuxTerminalSessionClientBase());
        session.mShellPid = 1;
        return session;
    }
    public static String read(TerminalSession session) {
        byte[] buffer = new byte[4096];
        int count = session.mTerminalToProcessIOQueue.read(buffer, false);
        return new String(buffer, 0, count, StandardCharsets.UTF_8);
    }
}
