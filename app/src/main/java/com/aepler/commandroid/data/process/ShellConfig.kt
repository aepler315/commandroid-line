// SPDX-License-Identifier: GPL-3.0-only
package com.aepler.commandroid.data.process

import java.io.File

/**
 * Static configuration for [ShellTerminalProcessManager]. Bound in `DataModule`; tests build it
 * directly to point at the host shell.
 */
data class ShellConfig(
    /** Absolute path of the POSIX shell started once per session. */
    val shellPath: String = resolveDefaultShell(),
    /** Variables applied to every new shell before the per-call environment. */
    val baseEnvironment: Map<String, String> = emptyMap(),
    /** Upper bound of undelivered characters buffered per command before the oldest are dropped. */
    val maxBufferedCharsPerCommand: Int = DEFAULT_MAX_BUFFERED_CHARS,
    /** How long to wait for pipe EOF after the shell process exited before forcing completion. */
    val exitGraceMillis: Long = DEFAULT_EXIT_GRACE_MILLIS
) {
    init {
        require(maxBufferedCharsPerCommand > 0) { "maxBufferedCharsPerCommand must be positive" }
    }

    companion object {
        const val ANDROID_SHELL = "/system/bin/sh"
        const val POSIX_SHELL = "/bin/sh"
        const val DEFAULT_MAX_BUFFERED_CHARS = 1 shl 20
        const val DEFAULT_EXIT_GRACE_MILLIS = 250L

        /** `/system/bin/sh` on Android, `/bin/sh` on a JVM host (unit tests). */
        fun resolveDefaultShell(): String =
            listOf(ANDROID_SHELL, POSIX_SHELL).firstOrNull { File(it).canExecute() } ?: ANDROID_SHELL
    }
}
