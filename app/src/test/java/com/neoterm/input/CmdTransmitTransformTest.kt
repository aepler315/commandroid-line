// SPDX-License-Identifier: GPL-3.0-only
package com.neoterm.input

import android.content.ComponentName
import android.content.Intent
import android.widget.CheckBox
import android.widget.TextView
import com.aepler.commandroid.domain.DefaultWindowsToLinuxTranslator
import com.aepler.commandroid.domain.TranslateCommandUseCase
import com.aepler.commandroid.domain.TranslationResult
import com.commandroid.runtime.FlavorRuntime
import com.termux.R
import com.termux.app.TermuxActivity
import com.termux.app.TermuxService
import com.termux.shared.models.ExecutionCommand
import com.termux.shared.shell.TermuxSession
import com.termux.terminal.SessionFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/** Pure JVM: the real cmd-translator behind the Transmit rewrite. */
class CmdTransmitTransformTest {
    private val home = "/data/data/com.commandroid_line/files/home"
    private val env = mapOf("HOME" to home, "FOO" to "bar")
    private val seenCwds = mutableListOf<File>()

    private fun transform(cwd: File? = File(home)) = CmdTransmitTransform(
        workingDirectory = { cwd },
        environment = { seenCwds += it; env },
    )

    private fun text(draft: String, cwd: File? = File(home)): String {
        val result = transform(cwd).rewrite(draft)
        assertTrue("Expected text for draft, got $result", result is Rewrite.Text)
        return (result as Rewrite.Text).text
    }

    private fun refusal(draft: String, cwd: File? = File(home)): String {
        val result = transform(cwd).rewrite(draft)
        assertTrue("Expected refusal, got $result", result is Rewrite.Refused)
        return (result as Rewrite.Refused).reason
    }

    @Test fun translatesSingleLineWithSessionEnvironmentAndCwd() {
        assertEquals("ls -l", text("dir"))
        assertEquals("ls -l", text("  dir  "))
        assertEquals("echo \${FOO}", text("echo %FOO%"))
        assertEquals(listOf(File(home)), seenCwds.distinct())
    }

    @Test fun nonCmdSingleLinePassesThrough() {
        assertEquals("ls -la", text("ls -la"))
    }

    @Test fun builtInActionsMapToShellCommands() {
        assertEquals("clear", text("cls"))
        assertEquals("exit", text("exit"))
        assertEquals(CmdTransmitTransform.HELP_COMMAND, text("help"))
        assertEquals(CmdTransmitTransform.HELP_COMMAND, text("dir /?"))
    }

    @Test fun refusalNamesTokenDiagnosticAndSuggestion() {
        assertTrue(refusal("regedit").startsWith("regedit: "))
        val script = refusal("build.bat")
        assertTrue(script, script.startsWith("build.bat: "))
        assertTrue(script, script.endsWith(" Try: sh <script>.sh"))
    }

    @Test fun blankDraftIsEmpty() {
        assertEquals("", text(" \n\n "))
    }

    @Test fun multiLineDraftIsOneSequencedPosixLine() {
        assertEquals("cd /storage/emulated/0/Users && pwd; ls -l", text("cd C:\\Users\n\n  dir  \n"))
        assertEquals("ls -l; cat a.txt", text("dir\ntype a.txt"))
        assertEquals("clear; ls -l", text("cls\ndir"))
    }

    @Test fun multiLineDraftWithoutCmdIsSentAsTyped() {
        // The `&` join is CMD syntax; in POSIX it would background the first line.
        assertEquals("ls -la\necho hi\n", text("ls -la\necho hi\n"))
    }

    @Test fun cdOnEarlierLineIsOnlyAPossibleDirectoryForTheDeletionGuard() {
        val translate = TranslateCommandUseCase(DefaultWindowsToLinuxTranslator())
        // Against the predicted directory alone ../.. is /storage/emulated/0/Download: allowed.
        assertTrue(translate("rd /s /q ..\\..", File("/storage/emulated/0/Download/x/y"), env)
            is TranslationResult.Translated)
        // But the cd may fail and the shell still runs the next line from $HOME, where ../..
        // contains home. One line with `&` is refused; separate lines must be refused as well.
        val sameLine = refusal("cd C:\\Download\\x\\y & rd /s /q ..\\..")
        val multiLine = refusal("cd C:\\Download\\x\\y\nrd /s /q ..\\..")
        assertTrue(multiLine, multiLine.contains("protected directory"))
        assertEquals(sameLine, multiLine.replace("\n", " & "))
        assertTrue(refusal("cd ..\nrd /s /q ..", File("$home/a")).contains("protected directory"))
    }

    @Test fun multiLineDraftWithUnbalancedQuoteIsRefused() {
        val reason = refusal("echo \"one\ndir")
        assertTrue(reason, reason.startsWith("line 1 has an unmatched double quote"))
        assertTrue(refusal("dir\necho a^").startsWith("line 2 ends with ^"))
        // Balanced quotes, escaped quotes and carets inside quotes join safely.
        assertEquals("cat 'a b'; ls -l", text("type \"a b\"\ndir"))
        assertNull(CmdTransmitTransform.joinHazard("echo ^\"x"))
        assertNull(CmdTransmitTransform.joinHazard("echo \"a^\""))
        assertNull(CmdTransmitTransform.joinHazard("echo a^^b"))
        assertTrue(CmdTransmitTransform.joinHazard("echo a^^^") != null)
        // A single line is never joined, so its own quoting is left to the translator.
        val single = transform().rewrite("echo \"one")
        assertFalse(single is Rewrite.Refused && single.reason.startsWith("line"))
    }

    @Test fun overLongJoinedDraftIsRefused() {
        val line = "echo " + "a".repeat(2100)
        val reason = refusal("$line\n$line")
        assertTrue(reason, reason.contains("one CMD command chain"))
        assertTrue(reason, reason.contains("limit is ${TranslateCommandUseCase.MAX_COMMAND_LENGTH}"))
    }

    @Test fun unknownWorkingDirectoryRefusesInsteadOfAssumingHome() {
        assertEquals(CmdTransmitTransform.UNKNOWN_DIRECTORY, refusal("dir", cwd = null))
        assertEquals(CmdTransmitTransform.UNKNOWN_DIRECTORY, refusal("rd /s /q ..", cwd = null))
        assertTrue(CmdTransmitTransform.UNKNOWN_DIRECTORY.startsWith("cannot determine the shell's working directory"))
        assertTrue(seenCwds.isEmpty())
    }

    @Test fun parsesEnvironmentEntries() {
        assertEquals(mapOf("A" to "1", "B" to "x=y", "C" to ""),
            CmdTransmitTransform.parseEnvironment(arrayOf("A=1", "B=x=y", "=bad", "noeq", "C=")))
    }

    @Test fun parsesForegroundProcessGroupFromStat() {
        val tail = "4194560 1 0 0 0 0 0 0 0 20 0 1 0 100 0 0"
        assertEquals(5678, CmdTransmitTransform.parseForegroundProcessGroup("1234 (bash) S 1 1234 1234 34816 5678 $tail"))
        assertEquals(99, CmdTransmitTransform.parseForegroundProcessGroup("1234 (my (odd) name) S 1 2 3 4 99 $tail"))
        assertEquals(99, CmdTransmitTransform.parseForegroundProcessGroup("1234 (a) b) R 1 2 3 4 99 $tail\n"))
        assertEquals(-1, CmdTransmitTransform.parseForegroundProcessGroup("7 (sh) S 1 7 7 0 -1 $tail"))
        assertNull(CmdTransmitTransform.parseForegroundProcessGroup("1234 bash S 1 2 3 4 99"))
        assertNull(CmdTransmitTransform.parseForegroundProcessGroup("1234 (bash) S 1 2"))
        assertNull(CmdTransmitTransform.parseForegroundProcessGroup(""))
    }

    @Test fun prefersForegroundProcessGroupCwdThenShellCwd() {
        val stats = mapOf(10 to "10 (sh) S 1 10 10 34816 20 0", 11 to "11 (sh) S 1 11 11 34816 0 0")
        val cwds = mapOf(20 to "/inner", 10 to "/outer")
        fun resolve(pid: Int, shellCwd: String?, cwd: Map<Int, String> = cwds) =
            CmdTransmitTransform.foregroundWorkingDirectory(pid, { shellCwd }, { stats[it] }, { cwd[it] })
        assertEquals(File("/inner"), resolve(10, "/outer"))
        // Foreground group's cwd unreadable (e.g. another uid): the shell's own cwd.
        assertEquals(File("/outer"), resolve(10, "/outer", cwd = mapOf(10 to "/outer")))
        // No foreground group (tpgid 0) or unreadable stat: the shell's own cwd.
        assertEquals(File("/shell"), resolve(11, "/shell"))
        assertEquals(File("/shell"), resolve(12, "/shell"))
        assertEquals(File("/shell"), resolve(-1, "/shell"))
        assertNull(resolve(12, null))
        assertNull(resolve(0, null))
    }
}

/** CMD and the runtime notice follow the selected session. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class CmdSessionScopeTest {
    @Before fun bindQueueOnlyService() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(app.packageName + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        val service = Robolectric.buildService(TermuxService::class.java).get()
        val constructor = TermuxSession::class.java.declaredConstructors.single()
        constructor.isAccessible = true
        service.termuxSessions.add(constructor.newInstance(SessionFixture.create(), ExecutionCommand(1), null, false) as TermuxSession)
        shadowOf(RuntimeEnvironment.application).setComponentNameAndServiceForBindService(
            ComponentName(RuntimeEnvironment.application, TermuxService::class.java), service.onBind(Intent()))
    }

    @Test fun sessionChangeTurnsCmdOffAndRefreshesRuntimeNotice() {
        val controller = Robolectric.buildActivity(TermuxActivity::class.java).create().start().resume()
        val activity = controller.get()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val cmd = activity.findViewById<CheckBox>(R.id.neoterm_cmd)
        val editor = activity.findViewById<CompositionEditText>(R.id.neoterm_draft)
        val notice = activity.findViewById<TextView>(R.id.neoterm_runtime_notice)
        cmd.isChecked = true
        assertTrue(editor.engine.transform is CmdTransmitTransform)
        notice.text = "stale"

        activity.onNeoTermSessionChanged(SessionFixture.create())

        assertFalse(cmd.isChecked)
        assertNull(editor.engine.transform)
        assertEquals(activity.getString(FlavorRuntime.runtimeNotice()), notice.text.toString())
        // The toggle still works for the new session.
        cmd.isChecked = true
        assertTrue(editor.engine.transform is CmdTransmitTransform)
        cmd.isChecked = false
        assertNull(editor.engine.transform)
        controller.pause().stop().destroy()
    }
}
