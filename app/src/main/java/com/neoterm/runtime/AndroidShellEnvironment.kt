// SPDX-License-Identifier: GPL-3.0-only
package com.neoterm.runtime

import android.content.Context
import com.termux.shared.shell.ShellEnvironmentClient
import java.io.File
import java.io.IOException

/** Independent Android-app shell. No com.termux bootstrap or loader is inherited. */
class AndroidShellEnvironment(context: Context) : ShellEnvironmentClient {
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
            val header = ByteArray(4)
            val length = file.inputStream().use { it.read(header) }
            val elf = length == 4 && header.contentEquals(byteArrayOf(127, 69, 76, 70))
            val shebang = length >= 2 && header[0] == '#'.code.toByte() && header[1] == '!'.code.toByte()
            if (!elf && !shebang) result.add("/system/bin/sh")
        }
        result.add(fileToExecute)
        arguments?.let { result.addAll(it) }
        return result.toTypedArray()
    }
}
