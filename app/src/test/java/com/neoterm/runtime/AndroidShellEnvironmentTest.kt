// SPDX-License-Identifier: GPL-3.0-only
package com.neoterm.runtime

import android.app.Application
import com.termux.shared.shell.ShellEnvironmentClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class AndroidShellEnvironmentTest {
    private val context get() = RuntimeEnvironment.application
    @Test fun createsOwnHomeAndTempWithoutRewritingExistingData() {
        val env = AndroidShellEnvironment(context)
        env.prepare()
        val home = File(context.filesDir, "home")
        assertEquals(home.absolutePath, env.defaultWorkingDirectoryPath)
        assertTrue(home.isDirectory)
        assertTrue(File(context.filesDir, "usr/tmp").isDirectory)
        val existing = File(home, "keep.txt").apply { writeText("preserve") }
        env.prepare()
        assertEquals("preserve", existing.readText())
        assertFalse(File(context.filesDir, "usr/bin/apt").exists())
    }
    @Test fun commandAndFailsafeUseSystemPathAndNoTermuxLoader() {
        val env: ShellEnvironmentClient = AndroidShellEnvironment(context)
        assertEquals("/system/bin", env.defaultBinPath)
        for (failsafe in listOf(false, true)) {
            val entries = env.buildEnvironment(context, failsafe, "/system")
            val values = entries.associate { val p=it.split('=',limit=2); p[0] to p[1] }
            assertEquals("/system/bin:/system/xbin", values["PATH"])
            assertEquals("/system/bin/sh", values["SHELL"])
            assertEquals("/system", values["PWD"])
            assertEquals(File(context.filesDir,"home").absolutePath, values["HOME"])
            assertEquals(File(context.filesDir,"usr").absolutePath, values["PREFIX"])
            assertEquals("xterm-256color", values["TERM"])
            assertEquals("system-shell", values["COMMANDROID_RUNTIME"])
            assertEquals("android-system", values["NEOTERM_RUNTIME"])
            assertEquals("commandroid-line > ", values["PS1"])
            assertFalse(values.containsKey("LD_PRELOAD"))
            assertFalse(values.containsKey("LD_LIBRARY_PATH"))
            assertFalse(entries.any { it.contains("/data/data/com.termux/") || it.endsWith("=null") })
        }
    }
    @Test fun plainScriptsUseSystemShellAndElfOrShebangKeepTheirInterpreter() {
        val env = AndroidShellEnvironment(context)
        val plain = File(context.cacheDir,"plain.sh").apply { writeText("printf hello\n") }
        assertArrayEquals(arrayOf("/system/bin/sh", plain.path, "arg"), env.setupProcessArgs(plain.path,arrayOf("arg")))
        // App-data scripts cannot be execve'd at targetSdk 29+ (W^X): run them via their interpreter.
        val shebang = File(context.cacheDir,"shebang.sh").apply { writeText("#!/system/bin/sh\nprintf hello\n") }
        assertArrayEquals(arrayOf("/system/bin/sh", shebang.path), env.setupProcessArgs(shebang.path,null))
        val withArg = File(context.cacheDir,"arg.sh").apply { writeText("#!/system/bin/sh -e\nprintf hello\n") }
        assertArrayEquals(arrayOf("/system/bin/sh", "-e", withArg.path, "x"), env.setupProcessArgs(withArg.path,arrayOf("x")))
        val prefixInterpreter = File(context.cacheDir,"prefix.sh").apply { writeText("#!/data/data/x/files/usr/bin/bash\necho\n") }
        assertArrayEquals(arrayOf("/system/bin/sh", prefixInterpreter.path), env.setupProcessArgs(prefixInterpreter.path,null))
        val outside = File(System.getProperty("java.io.tmpdir"), "neoterm-shebang-${System.nanoTime()}.sh").apply { writeText("#!/system/bin/sh\n") }
        try { assertArrayEquals(arrayOf(outside.path), env.setupProcessArgs(outside.path,null)) } finally { outside.delete() }
        val elf = File(context.cacheDir,"elf").apply { writeBytes(byteArrayOf(127,69,76,70,0)) }
        assertArrayEquals(arrayOf(elf.path),env.setupProcessArgs(elf.path,null))
        assertArrayEquals(arrayOf("/system/bin/sh","-c","pwd"),env.setupProcessArgs("/system/bin/sh",arrayOf("-c","pwd")))
    }
    @Test fun failedDirectorySetupDoesNotOverwriteAFile() {
        val home = File(context.filesDir,"home")
        home.deleteRecursively(); home.writeText("do not overwrite")
        try { AndroidShellEnvironment(context).prepare(); fail("Expected setup to fail") }
        catch (_: IOException) { assertEquals("do not overwrite",home.readText()) }
        finally { home.delete() }
    }
}
