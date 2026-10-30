// SPDX-License-Identifier: GPL-3.0-only
package com.neoterm.runtime

import android.content.Context
import com.termux.shared.shell.ShellEnvironmentClient
import java.io.File
import java.io.IOException

/** Independent Android-app shell. No com.termux bootstrap or loader is inherited. */
class AndroidShellEnvironment(context: Context) : ShellEnvironmentClient {
    /** App-private data root; files below it cannot be execve'd at targetSdk 29+ (W^X). */
    private val appDataRoot: File? = context.filesDir.parentFile
    private val home = File(context.filesDir, "home")
    private val prefix = File(context.filesDir, "usr")
    private val temporary = File(prefix, "tmp")

    @Throws(IOException::class)
    fun prepare() {
        for (directory in listOf(home, temporary)) {
            if (!directory.isDirectory && !directory.mkdirs())
                throw IOException("Cannot create NeoTerm runtime directories")
        }
    }

    override fun getDefaultWorkingDirectoryPath(): String = home.absolutePath
    override fun getDefaultBinPath(): String = "/system/bin"

    override fun buildEnvironment(currentPackageContext: Context, isFailSafe: Boolean, workingDirectory: String?): Array<String> {
        val environment = mutableListOf(
            "HOME=${home.absolutePath}", "PREFIX=${prefix.absolutePath}",
            "TMPDIR=${temporary.absolutePath}", "PWD=${workingDirectory ?: home.absolutePath}",
            "PATH=/system/bin:/system/xbin", "SHELL=/system/bin/sh",
            "TERM=xterm-256color", "COLORTERM=truecolor", "LANG=C.UTF-8",
            "NEOTERM_RUNTIME=android-system", "PS1=NeoTerm > "
        )
        // Preserve Android runtime support for platform command wrappers, never arbitrary
        // inherited PATH, shell initialization or LD_PRELOAD/LD_LIBRARY_PATH injection.
        for (name in listOf("ANDROID_ROOT", "ANDROID_DATA", "ANDROID_ART_ROOT", "ANDROID_I18N_ROOT",
            "ANDROID_RUNTIME_ROOT", "ANDROID_TZDATA_ROOT", "BOOTCLASSPATH", "DEX2OATBOOTCLASSPATH", "EXTERNAL_STORAGE")) {
            System.getenv(name)?.takeIf { it.isNotEmpty() }?.let { environment.add("$name=$it") }
        }
        return environment.toTypedArray()
    }

    override fun setupProcessArgs(fileToExecute: String, arguments: Array<out String>?): Array<String> {
        val result = mutableListOf<String>()
        val file = File(fileToExecute)
        if (file.isFile) {
            val header = ByteArray(SHEBANG_LINE_MAX)
            val length = file.inputStream().use { it.read(header) }
            val elf = length >= 4 && header[0] == 127.toByte() && header[1] == 'E'.code.toByte() &&
                header[2] == 'L'.code.toByte() && header[3] == 'F'.code.toByte()
            val shebang = length >= 2 && header[0] == '#'.code.toByte() && header[1] == '!'.code.toByte()
            when {
                !elf && !shebang -> result.add(SYSTEM_SHELL)
                // Since targetSdk 29, SELinux denies execve() of files in the app's own data
                // directory, so a script there cannot be started directly even when it is
                // executable. Hand it to its interpreter instead (or to the system shell when
                // that interpreter is not a system binary). ELF files there still cannot run.
                shebang && isInAppData(file) -> result.addAll(interpreterFor(String(header, 2, length - 2, Charsets.UTF_8)))
            }
        }
        result.add(fileToExecute)
        arguments?.let { result.addAll(it) }
        return result.toTypedArray()
    }

    private fun isInAppData(file: File): Boolean {
        val root = appDataRoot ?: return false
        return try {
            file.canonicalPath.startsWith(root.canonicalPath + File.separator)
        } catch (_: IOException) {
            file.absolutePath.startsWith(root.absolutePath + File.separator)
        }
    }

    /** Interpreter command for a shebang line body (text after `#!`); never an app-data binary. */
    private fun interpreterFor(shebangBody: String): List<String> {
        val line = shebangBody.substringBefore('\n').trim()
        val parts = line.split(' ', '\t').filter { it.isNotEmpty() }
        val interpreter = parts.firstOrNull() ?: return listOf(SYSTEM_SHELL)
        if (SYSTEM_PREFIXES.none { interpreter.startsWith(it) }) return listOf(SYSTEM_SHELL)
        // Linux passes everything after the interpreter as one optional argument.
        val argument = line.substring(line.indexOf(interpreter) + interpreter.length).trim()
        return if (argument.isEmpty()) listOf(interpreter) else listOf(interpreter, argument)
    }

    private companion object {
        const val SYSTEM_SHELL = "/system/bin/sh"
        const val SHEBANG_LINE_MAX = 256
        val SYSTEM_PREFIXES = listOf("/system/", "/apex/", "/vendor/", "/product/", "/odm/")
    }
}
