// SPDX-License-Identifier: GPL-3.0-only
package com.aepler.commandroid.presentation

import android.annotation.SuppressLint
import java.io.File

/** Renders a POSIX working directory as a CMD-style prompt, e.g. `/storage/emulated/0/Download` -> `C:\Download>`. */
@SuppressLint("SdCardPath") // Fixed CMD drive mapping, not a storage lookup.
object WindowsPromptFormatter {
    const val STORAGE_ROOT = "/storage/emulated/0"
    private const val SDCARD_ALIAS = "/sdcard"

    fun format(workingDirectory: File): String {
        val path = workingDirectory.absoluteFile.normalize().path.trimEnd('/')
        val relative = when {
            path.isEmpty() -> ""
            path == STORAGE_ROOT || path == SDCARD_ALIAS -> ""
            path.startsWith("$STORAGE_ROOT/") -> path.removePrefix("$STORAGE_ROOT/")
            path.startsWith("$SDCARD_ALIAS/") -> path.removePrefix("$SDCARD_ALIAS/")
            else -> path.removePrefix("/")
        }
        return "C:\\" + relative.replace('/', '\\') + ">"
    }
}
