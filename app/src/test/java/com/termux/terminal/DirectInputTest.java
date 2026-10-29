// SPDX-License-Identifier: GPL-3.0-only
package com.termux.terminal;

import android.view.KeyEvent;
import android.view.InputDevice;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import com.termux.shared.terminal.TermuxTerminalSessionClientBase;
import com.termux.shared.terminal.TermuxTerminalViewClientBase;
import com.termux.view.TerminalView;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

/** Capture the real session queue without spawning a Linux process/JNI. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = android.app.Application.class)
public class DirectInputTest {
    private TerminalSession session;
    private TerminalView view;
    private final List<String> logs = new ArrayList<>();

    @Before public void setup() throws Exception {
        session = new TerminalSession("/unused", "/unused", new String[0], new String[0], null,
            new TermuxTerminalSessionClientBase());
        session.mEmulator = new TerminalEmulator(session, 80, 24, 10, 15, null,
            new TermuxTerminalSessionClientBase());
        session.mShellPid = 1; // test queue capture only; no native process is created.
        view = new TerminalView(RuntimeEnvironment.application, null);
        view.setTerminalViewClient(new TermuxTerminalViewClientBase() {
            @Override public void logInfo(String tag, String message) { logs.add(message); }
        });
        view.attachSession(session);
        Field emulator = TerminalView.class.getDeclaredField("mEmulator");
        emulator.setAccessible(true);
        emulator.set(view, session.mEmulator);
    }
    private String read() {
        byte[] buffer = new byte[4096];
        int n = session.mTerminalToProcessIOQueue.read(buffer, false);
        return new String(buffer, 0, n, StandardCharsets.UTF_8);
    }
    @Test public void onePhysicalEnterCommitsPendingDirectTextThenSubmitsOnce() {
        InputConnection ic = view.onCreateInputConnection(new EditorInfo());
        ic.setComposingText("pwd", 1);
        assertEquals("", read());
        KeyEvent down = new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER);
        KeyEvent up = new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER);
        down.setSource(InputDevice.SOURCE_KEYBOARD);
        up.setSource(InputDevice.SOURCE_KEYBOARD);
        assertTrue(view.onKeyPreIme(KeyEvent.KEYCODE_ENTER, down));
        assertTrue(view.onKeyPreIme(KeyEvent.KEYCODE_ENTER, up));
        assertEquals("pwd\r", read());
        assertFalse("Late IME commit must not replay a submitted command", ic.commitText("pwd", 1));
        assertEquals("", read());
    }
    @Test public void physicalEnterAndKeypadEnterKeepUpstreamLineEnding() {
        for (int code : new int[]{KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER}) {
            KeyEvent down = new KeyEvent(KeyEvent.ACTION_DOWN, code);
            down.setSource(InputDevice.SOURCE_KEYBOARD);
            assertTrue(view.onKeyPreIme(code, down));
            assertEquals(code == KeyEvent.KEYCODE_ENTER ? "\r" : "\n", read());
        }
    }
    @Test public void composerOwnershipDoesNotSubmitThroughPhysicalPreImeEnter() {
        view.setInputConnectionEnabled(false);
        KeyEvent event = new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER);
        event.setSource(InputDevice.SOURCE_KEYBOARD);
        assertFalse(view.onKeyPreIme(KeyEvent.KEYCODE_ENTER, event));
        assertEquals("", read());
    }
    @Test public void shellCommandsAndFlagsRemainExact() {
        InputConnection ic = view.onCreateInputConnection(new EditorInfo());
        ic.commitText("ls /tmp/Case --all", 1);
        assertEquals("ls /tmp/Case --all", read());
    }
    @Test public void controlsEscapeAndAltKeepUpstreamBytes() {
        view.inputCodePoint('c', true, false);
        view.inputCodePoint('d', true, false);
        view.inputCodePoint('z', true, false);
        view.inputCodePoint('x', false, true);
        view.onKeyDown(KeyEvent.KEYCODE_DPAD_UP, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP));
        assertEquals("\u0003\u0004\u001a\u001bx\u001b[A", read());
    }
    @Test public void enterAndBackspaceRemainDirect() {
        InputConnection ic = view.onCreateInputConnection(new EditorInfo());
        ic.commitText("pwd", 1);
        view.onKeyDown(KeyEvent.KEYCODE_DEL, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL));
        ic.commitText("\n", 1);
        assertEquals("pwd\u007f\r", read());
    }
    @Test public void directInputDiagnosticsNeverRecordPasswords() {
        view.setIsTerminalViewKeyLoggingEnabled(true);
        view.onCreateInputConnection(new EditorInfo()).commitText("secret-password", 1);
        view.inputCodePoint('s', false, false);
        assertTrue(logs.isEmpty());
        assertEquals("secret-passwords", read());
    }
    @Test public void oldDirectConnectionCannotWriteAfterComposerOpened() {
        InputConnection old = view.onCreateInputConnection(new EditorInfo());
        old.setComposingText("pending", 1);
        view.setInputConnectionEnabled(false);
        assertFalse(old.finishComposingText());
        assertFalse(old.commitText("late", 1));
        assertFalse(old.deleteSurroundingText(1, 0));
        assertEquals("", read());
        view.setInputConnectionEnabled(true);
        assertFalse(old.commitText("stale", 1));
        view.onCreateInputConnection(new EditorInfo()).commitText("fresh", 1);
        assertEquals("fresh", read());
    }
    @Test public void bracketedPasteCapabilityTracksActualDecset() {
        assertFalse(session.mEmulator.isBracketedPasteModeEnabled());
        byte[] enable = "\u001b[?2004h".getBytes(StandardCharsets.UTF_8);
        session.mEmulator.append(enable, enable.length);
        assertTrue(session.mEmulator.isBracketedPasteModeEnabled());
        byte[] disable = "\u001b[?2004l".getBytes(StandardCharsets.UTF_8);
        session.mEmulator.append(disable, disable.length);
        assertFalse(session.mEmulator.isBracketedPasteModeEnabled());
    }
}
