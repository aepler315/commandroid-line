// SPDX-License-Identifier: GPL-3.0-only
package com.aepler.commandroid.domain

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WindowsToLinuxTranslatorTest {

    private val translator = DefaultWindowsToLinuxTranslator()
    private val cwd = File("/storage/emulated/0/Download")
    private val home = "/data/user/0/com.neoterm/files/home"
    private val env = mapOf("HOME" to home, "PATH" to "/system/bin", "TMPDIR" to "/data/local/tmp")

    private fun translate(command: String, dir: File = cwd): TranslationResult =
        translator.translate(command, dir, env)

    private fun posix(command: String, dir: File = cwd): String {
        val result = translate(command, dir)
        if (result !is TranslationResult.Translated) fail("Expected Translated for <$command> but was $result")
        return (result as TranslationResult.Translated).posixCommand
    }

    private fun translated(command: String, dir: File = cwd): TranslationResult.Translated {
        val result = translate(command, dir)
        if (result !is TranslationResult.Translated) fail("Expected Translated for <$command> but was $result")
        return result as TranslationResult.Translated
    }

    private fun error(command: String, dir: File = cwd): TranslationResult.UnsupportedCommandError {
        val result = translate(command, dir)
        if (result !is TranslationResult.UnsupportedCommandError) {
            fail("Expected UnsupportedCommandError for <$command> but was $result")
        }
        return result as TranslationResult.UnsupportedCommandError
    }

    private fun builtIn(command: String): BuiltInTerminalAction {
        val result = translate(command)
        if (result !is TranslationResult.BuiltInAction) fail("Expected BuiltInAction for <$command> but was $result")
        return (result as TranslationResult.BuiltInAction).action
    }

    // ---------------------------------------------------------------- dir

    @Test fun dirVariants() {
        assertEquals("ls -l", posix("dir"))
        assertEquals("ls -la", posix("dir /a"))
        assertEquals("ls -la", posix("dir /a:h"))
        assertEquals("ls -1", posix("dir /b"))
        assertEquals("ls -1a", posix("dir /b /a"))
        assertEquals("find .", posix("dir /s"))
        assertEquals("find .", posix("dir /a /s"))
        assertEquals("find .", posix("dir /s/b"))
        assertEquals("find . -name '*.txt'", posix("dir /s *.txt"))
        assertEquals("ls -la *.txt", posix("DIR /A *.txt"))
        assertEquals("ls -l /storage/emulated/0/Music", posix("dir C:\\Music"))
        assertEquals("ls -l /storage/emulated/0/", posix("dir C:\\"))
        assertEquals("ls -l /sdcard", posix("dir /sdcard"))
        assertEquals("find /storage/emulated/0/DCIM -name '*.jpg'", posix("dir /s C:\\DCIM\\*.jpg"))
    }

    @Test fun bannerReportsTranslation() {
        val result = translated("dir /a")
        assertEquals("[CMD -> SH] dir /a -> ls -la", result.explanation)
        assertEquals("dir /a", result.originalCommand)
    }

    // ---------------------------------------------------------------- built-ins

    @Test fun builtIns() {
        assertEquals(BuiltInTerminalAction.CLEAR_SCREEN, builtIn("cls"))
        assertEquals(BuiltInTerminalAction.CLEAR_SCREEN, builtIn("CLS"))
        assertEquals(BuiltInTerminalAction.EXIT_SESSION, builtIn("exit"))
        assertEquals(BuiltInTerminalAction.EXIT_SESSION, builtIn("Exit /b 0"))
        assertEquals(BuiltInTerminalAction.PRINT_HELP, builtIn("help"))
        assertEquals(BuiltInTerminalAction.PRINT_HELP, builtIn("dir /?"))
    }

    @Test fun builtInsInsideChains() {
        assertEquals("clear && ls -l", posix("cls && dir"))
        assertEquals("ls -l && exit", posix("dir && exit"))
    }

    // ---------------------------------------------------------------- cd

    @Test fun cdNormalizesAndTracksDirectory() {
        val result = translated("cd C:\\Users\\Docs")
        assertEquals("cd /storage/emulated/0/Users/Docs && pwd", result.posixCommand)
        assertEquals(File("/storage/emulated/0/Users/Docs"), result.updatedWorkingDirectory)
    }

    @Test fun cdVariants() {
        assertEquals("cd .. && pwd", posix("cd .."))
        assertEquals(File("/storage/emulated/0"), translated("cd ..").updatedWorkingDirectory)
        assertEquals(File("/storage/emulated/0"), translated("cd..").updatedWorkingDirectory)
        assertEquals("cd ../Music && pwd", posix("chdir ..\\Music"))
        assertEquals(File("/storage/emulated/0/Music"), translated("chdir ..\\Music").updatedWorkingDirectory)
        assertEquals("cd /storage/emulated/0/ && pwd", posix("cd /d C:\\"))
        assertEquals(File("/storage/emulated/0"), translated("cd /d C:\\").updatedWorkingDirectory)
        assertEquals("cd / && pwd", posix("cd \\"))
        assertEquals("cd /sdcard && pwd", posix("cd /sdcard"))
        assertEquals(File("/sdcard"), translated("cd /sdcard").updatedWorkingDirectory)
        assertEquals("cd sub && pwd", posix("CD sub"))
        assertEquals(File("/storage/emulated/0/Download/sub"), translated("cd sub").updatedWorkingDirectory)
    }

    @Test fun bareCdPrintsWorkingDirectory() {
        val result = translated("cd")
        assertEquals("pwd", result.posixCommand)
        assertNull(result.updatedWorkingDirectory)
    }

    @Test fun cdWithSpaces() {
        val quoted = translated("cd \"C:\\My Documents\\Work Files\"")
        assertEquals("cd '/storage/emulated/0/My Documents/Work Files' && pwd", quoted.posixCommand)
        assertEquals(File("/storage/emulated/0/My Documents/Work Files"), quoted.updatedWorkingDirectory)
        val unquoted = translated("cd My Documents")
        assertEquals("cd 'My Documents' && pwd", unquoted.posixCommand)
        assertEquals(File("/storage/emulated/0/Download/My Documents"), unquoted.updatedWorkingDirectory)
    }

    @Test fun cdWithVariable() {
        val result = translated("cd %USERPROFILE%\\projects")
        assertEquals("cd \"\${HOME}\"/projects && pwd", result.posixCommand)
        assertEquals(File("$home/projects"), result.updatedWorkingDirectory)
    }

    @Test fun cdInAndChainTracksLastDirectory() {
        val result = translated("cd C:\\Music && cd rock")
        assertEquals("cd /storage/emulated/0/Music && pwd && cd rock && pwd", result.posixCommand)
        assertEquals(File("/storage/emulated/0/Music/rock"), result.updatedWorkingDirectory)
        assertNull(translated("cd C:\\Music | dir").updatedWorkingDirectory)
    }

    @Test fun driveSwitch() {
        val result = translated("C:")
        assertEquals("cd /storage/emulated/0 && pwd", result.posixCommand)
        assertEquals(File("/storage/emulated/0"), result.updatedWorkingDirectory)
        assertEquals("D:", error("D:").offendingToken)
    }

    // ---------------------------------------------------------------- path normalization

    @Test fun pathNormalization() {
        assertEquals("cat /storage/emulated/0/a/b.txt", posix("type C:\\a\\b.txt"))
        assertEquals("cat /storage/emulated/0/a/b.txt", posix("type c:/a/b.txt"))
        assertEquals("cat ../../x.txt", posix("type ..\\..\\x.txt"))
        assertEquals("cat sub/x.txt", posix("type sub\\x.txt"))
        assertEquals("cat /storage/emulated/0", posix("type C:"))
    }

    @Test fun otherDrivesAreRejected() {
        val err = error("type D:\\data.txt")
        assertEquals("D:", err.offendingToken)
        assertTrue(err.diagnosticExplanation.contains("/storage/emulated/0"))
        assertEquals("/storage/emulated/0/", err.suggestedEquivalent)
    }

    @Test fun uncPathsAreRejected() {
        error("type \\\\server\\share\\file.txt")
    }

    // ---------------------------------------------------------------- type

    @Test fun typeMultiFileAndQuotedPaths() {
        assertEquals("cat a.txt b.txt", posix("type a.txt b.txt"))
        assertEquals(
            "cat '/storage/emulated/0/My Documents/notes.txt' plain.txt",
            posix("type \"C:\\My Documents\\notes.txt\" plain.txt")
        )
        assertEquals("cat 'file.txt'", posix("type \"file.txt\""))
        error("type")
    }

    @Test fun mixedQuotingInsidePaths() {
        assertEquals("ls -l '/storage/emulated/0/Program Files'", posix("dir \"C:\\Program Files\""))
        assertEquals("ls -l '/storage/emulated/0/Program Files/app'", posix("dir C:\\\"Program Files\"\\app"))
    }

    // ---------------------------------------------------------------- copy / xcopy / move / ren

    @Test fun copyAndXcopy() {
        assertEquals("cp a.txt b.txt", posix("copy a.txt b.txt"))
        assertEquals("cp -f a.txt /storage/emulated/0/backup/", posix("copy /y a.txt C:\\backup\\"))
        assertEquals("cp a.txt .", posix("copy a.txt"))
        assertEquals("cp -r src dst", posix("xcopy /e src dst"))
        assertEquals("cp -rf src dst", posix("xcopy src dst /E /Y"))
        assertEquals("cp -r src dst", posix("xcopy /s /i src dst"))
        assertEquals("cp 'my file.txt' '/storage/emulated/0/dest dir/'", posix("copy \"my file.txt\" \"C:\\dest dir\\\""))
        error("copy a+b c")
    }

    @Test fun moveAndRename() {
        assertEquals("mv a.txt ../b.txt", posix("move a.txt ..\\b.txt"))
        assertEquals("mv -f a b", posix("move /y a b"))
        assertEquals("mv 'old name.txt' 'new name.txt'", posix("ren \"old name.txt\" \"new name.txt\""))
        assertEquals("mv /storage/emulated/0/docs/a.txt /storage/emulated/0/docs/b.txt", posix("rename C:\\docs\\a.txt b.txt"))
        error("ren onlyone")
    }

    // ---------------------------------------------------------------- del / rmdir

    @Test fun delVariants() {
        assertEquals("rm -f file.txt", posix("del file.txt"))
        assertEquals("rm -f a.txt b.log", posix("erase /q a.txt b.log"))
        assertEquals("rm -f *.tmp", posix("del /f /q *.tmp"))
        assertEquals("rm -f /storage/emulated/0/x.txt", posix("del C:\\x.txt"))
        assertEquals("find . -type f -name '*.tmp' -delete", posix("del /s /q *.tmp"))
        assertEquals("find /storage/emulated/0/logs -type f -name '*.log' -delete", posix("del /s C:\\logs\\*.log"))
        assertEquals("rm -f \"\${TMPDIR:?}\"/junk", posix("del %TEMP%\\junk"))
    }

    @Test fun rmdirFlags() {
        assertEquals("rm -rf old", posix("rmdir /s /q old"))
        assertEquals("rm -rf old", posix("rd /s/q old"))
        assertEquals("rm -r old", posix("rd /s old"))
        assertEquals("rmdir old", posix("rd old"))
        assertEquals("rmdir old", posix("rd /q old"))
        assertEquals("rm -rf '/storage/emulated/0/Old Stuff'", posix("RD /S /Q \"C:\\Old Stuff\""))
        assertEquals("rm -rf ../sibling", posix("rmdir /s /q ..\\sibling"))
    }

    @Test fun rootDeletionGuard() {
        listOf(
            "rmdir /s /q /",
            "rd /s /q \\",
            "rd /s /q /*",
            "del /",
            "del /*",
            "del \\*.*",
            "rd /s /q C:\\",
            "rd /s /q C:",
            "rmdir /s /q /storage/emulated/0",
            "rd /s /q /sdcard",
            "rd /s /q /system",
            "rd /s /q \"\"",
            "rd /s /q %UNSET_VARIABLE%",
            "rd /s /q %UNSET_VARIABLE%\\*",
            "rd /s /q /s*",
            "del /s /q C:\\*",
            "del /s \\*.txt",
            "del C:\\*"
        ).forEach { command ->
            val result = translate(command)
            assertTrue("$command must be refused, was $result", result is TranslationResult.UnsupportedCommandError)
        }
        // Relative traversal that lands on the storage root is refused too.
        error("rd /s /q ..", File("/storage/emulated/0/Download"))
        error("rd /s /q .", File("/storage/emulated/0"))
        error("rd /s /q *", File("/storage/emulated/0"))
        // HOME is protected from recursive deletion.
        error("rd /s /q %USERPROFILE%")
    }

    @Test fun guardAllowsOrdinaryTargets() {
        assertEquals("rm -rf /storage/emulated/0/Download/cache", posix("rd /s /q C:\\Download\\cache"))
        assertEquals("rm -rf build", posix("rd /s /q build"))
        assertEquals("rm -f *", posix("del *"))
    }

    // ---------------------------------------------------------------- findstr

    @Test fun findstrFlags() {
        assertEquals("grep -i -n 'error' log.txt", posix("findstr /i /n \"error\" log.txt"))
        assertEquals("grep -i 'error' log.txt", posix("findstr /I \"error\" log.txt"))
        assertEquals("grep -E -v '^#' conf.txt", posix("findstr /r /v \"^#\" conf.txt"))
        assertEquals("grep -e 'foo' -e 'bar' a.txt", posix("findstr \"foo bar\" a.txt"))
        assertEquals("grep 'foo bar' a.txt", posix("findstr /c:\"foo bar\" a.txt"))
        assertEquals("grep TODO /storage/emulated/0/src/main.c", posix("findstr TODO C:\\src\\main.c"))
        assertEquals("grep -i -n 'x' a b", posix("findstr /i/n \"x\" a b"))
    }

    @Test fun findstrInPipe() {
        assertEquals("ls -l | grep -i 'txt'", posix("dir | findstr /i \"txt\""))
        assertEquals("ps -A | grep -i 'chrome'", posix("tasklist | findstr /i \"chrome\""))
    }

    // ---------------------------------------------------------------- where / ipconfig / tasks

    @Test fun whereIpconfigTasks() {
        assertEquals("which sh", posix("where sh"))
        assertEquals("which ls toybox", posix("where ls toybox"))
        assertEquals("ip addr show", posix("ipconfig"))
        assertEquals("ifconfig -a", posix("ipconfig /all"))
        assertEquals("ifconfig -a", posix("IPCONFIG /ALL"))
        error("ipconfig /release")
        assertEquals("ps -A", posix("tasklist"))
        assertEquals("ps -A", posix("tasklist /v"))
    }

    @Test fun taskkill() {
        assertEquals("kill -9 1234", posix("taskkill /pid 1234 /f"))
        assertEquals("kill -9 1234", posix("taskkill /F /PID 1234"))
        assertEquals("kill 42", posix("taskkill /pid 42"))
        assertEquals("pkill -f notepad", posix("taskkill /im notepad.exe"))
        assertEquals("pkill -9 -f com.example.app", posix("taskkill /f /im com.example.app"))
        assertEquals("pkill -f 'my app'", posix("taskkill /im \"my app\""))
        error("taskkill /pid abc")
        error("taskkill")
    }

    // ---------------------------------------------------------------- variables

    @Test fun envVariableRewriting() {
        assertEquals("echo \${HOME}", posix("echo %HOME%"))
        assertEquals("echo \${PATH}", posix("echo %path%"))
        assertEquals("echo \${HOME}", posix("echo %USERPROFILE%"))
        assertEquals("echo \${FOO}", posix("echo %FOO%"))
        assertEquals("echo \"home=\${HOME} ok\"", posix("echo \"home=%HOME% ok\""))
        assertEquals("echo 100%", posix("echo 100%%"))
        assertEquals("cat \"\${HOME}\"/notes.txt", posix("type %HOME%\\notes.txt"))
        val result = translated("echo %HOME%")
        assertEquals("[CMD -> SH] echo %HOME% -> echo \${HOME}", result.explanation)
    }

    @Test fun plainEchoPassesThrough() {
        val result = translated("echo hello world")
        assertEquals("echo hello world", result.posixCommand)
        assertNull(result.explanation)
        assertEquals("echo \$HOME", posix("echo \$HOME"))
    }

    @Test fun setVariable() {
        assertEquals("export GREETING=hello", posix("set GREETING=hello"))
        assertEquals("export MSG='hello world'", posix("set MSG=hello world"))
        assertEquals("unset MSG", posix("set MSG="))
        assertEquals("set -e", posix("set -e"))
        error("set /a X=1+1")
    }

    // ---------------------------------------------------------------- POSIX passthrough

    @Test fun posixCommandsPassThroughUnchanged() {
        listOf(
            "ls -la",
            "cat /proc/cpuinfo",
            "grep -rn foo .",
            "pwd",
            "ls | grep x && echo ok",
            "sleep 5 &",
            "echo \$((1+2)) > /dev/null 2>&1",
            "mkdir -p a/b",
            "rmdir emptydir",
            "date +%Y%m%d"
        ).forEach { command ->
            val result = translated(command)
            assertEquals(command, result.posixCommand)
            assertNull("explanation for $command", result.explanation)
            assertNull(result.updatedWorkingDirectory)
        }
    }

    @Test fun commandNamesAreCaseInsensitive() {
        assertEquals("ls -la", posix("DiR /A"))
        assertEquals("cat a", posix("TYPE a"))
        assertEquals("which sh", posix("Where sh"))
        assertEquals("ip addr show", posix("ipconfig.exe"))
    }

    // ---------------------------------------------------------------- chains & redirects

    @Test fun chainsTranslateEachSegment() {
        assertEquals("ls -l | grep -i 'x'", posix("dir | findstr /i \"x\""))
        assertEquals("mkdir -p out && cp a.txt out/", posix("md out && copy a.txt out\\"))
        assertEquals("ls -l || echo failed", posix("dir || echo failed"))
        assertEquals("ls -l; cat a.txt", posix("dir & type a.txt"))
        assertEquals("ls -l | wc -l", posix("dir | wc -l"))
    }

    @Test fun redirects() {
        assertEquals("ls -l > /storage/emulated/0/list.txt", posix("dir > C:\\list.txt"))
        assertEquals("ls -l >/dev/null", posix("dir >nul"))
        assertEquals("cat a 2>/dev/null", posix("type a 2>NUL"))
        assertEquals("ls -l >> 'out file.txt' 2>&1", posix("dir >> \"out file.txt\" 2>&1"))
    }

    // ---------------------------------------------------------------- injection safety

    @Test fun quotedArgumentsCannotInjectShellSyntax() {
        assertEquals("cat '\$(reboot)'", posix("type \"\$(reboot)\""))
        assertEquals("cat 'a'\\''; rm -rf / #.txt'", posix("type \"a'; rm -rf / #.txt\""))
        assertEquals("cat 'a;b'", posix("type a;b"))
        assertEquals("cat '`id`'", posix("type `id`"))
        assertEquals("grep -i '\$HOME' f", posix("findstr /i \"\$HOME\" f"))
        assertEquals("echo \"\${HOME}\\\$(id)\"", posix("echo \"%HOME%\$(id)\""))
        assertEquals("cat '&&'", posix("type ^&^&"))
    }

    // ---------------------------------------------------------------- unsupported tools

    @Test fun unsupportedWin32Tools() {
        val regedit = error("regedit")
        assertEquals("regedit", regedit.offendingToken)
        assertEquals("getprop", regedit.suggestedEquivalent)
        assertTrue(regedit.diagnosticExplanation.isNotBlank())

        val wmic = error("wmic cpu get name")
        assertEquals("wmic", wmic.offendingToken)
        assertNotNull(wmic.suggestedEquivalent)

        assertEquals("REG", error("REG query HKLM\\Software").offendingToken)
        error("sc query")
        error("net user")
        error("powershell -Command Get-Process")
        assertEquals("ps -A", error("Get-Process").suggestedEquivalent)
        assertEquals("ls -la", error("Get-ChildItem").suggestedEquivalent)
        error("setup.exe")
        error("build.bat")
        error("wmic.exe os get caption")
        error("dir | wmic")
    }

    @Test fun unknownSwitchesAreDiagnosed() {
        assertEquals("/z", error("del /z file").offendingToken)
    }

    @Test fun blankInput() {
        val result = translated("   ")
        assertEquals("", result.posixCommand)
        assertNull(result.explanation)
    }

    // ---------------------------------------------------------------- use case

    @Test fun useCaseValidatesAndDelegates() {
        val useCase = TranslateCommandUseCase(translator)
        val blank = useCase("  \t ", cwd, env)
        assertEquals(TranslationResult.Translated("", "", null), blank)
        val dir = useCase("  dir /a  ", cwd, env) as TranslationResult.Translated
        assertEquals("ls -la", dir.posixCommand)
        assertEquals("dir /a", dir.originalCommand)
        assertTrue(useCase("echo " + "x".repeat(5000), cwd, env) is TranslationResult.UnsupportedCommandError)
        assertTrue(useCase("dir\nrm -rf x", cwd, env) is TranslationResult.UnsupportedCommandError)
    }
}
