// SPDX-License-Identifier: GPL-3.0-only
package com.neoterm.input

import com.aepler.commandroid.domain.BuiltInTerminalAction
import com.aepler.commandroid.domain.DefaultWindowsToLinuxTranslator
import com.aepler.commandroid.domain.TranslateCommandUseCase
import com.aepler.commandroid.domain.TranslationResult
import java.io.File

/**
 * Commandroid inside the PTY terminal: translates a Windows CMD draft to POSIX at Transmit.
 *
 * The translation is written to the shell's line editor like any draft, so the user sees the
 * POSIX command before pressing Enter; nothing here runs a process. Each line of a multi-line
 * draft is translated separately, and a `cd` on one line is predicted for the next (a prediction
 * only: lines run later, in the shell). [workingDirectory] and [environment] are snapshots of the
 * session's shell, which the shell may since have changed.
 */
class CmdTransmitTransform(
    private val workingDirectory: () -> File,
    private val environment: () -> Map<String, String>,
    private val translate: TranslateCommandUseCase = TranslateCommandUseCase(DefaultWindowsToLinuxTranslator()),
) : TransmitTransform {

    override fun rewrite(draft: String): Rewrite {
        var cwd = workingDirectory()
        val env = environment()
        val out = ArrayList<String>()
        for (line in draft.split('\n')) {
            when (val result = translate(line, cwd, env)) {
                is TranslationResult.Translated -> {
                    out += result.posixCommand
                    result.updatedWorkingDirectory?.let { cwd = it }
                }
                is TranslationResult.BuiltInAction -> out += when (result.action) {
                    BuiltInTerminalAction.CLEAR_SCREEN -> "clear"
                    BuiltInTerminalAction.EXIT_SESSION -> "exit"
                    BuiltInTerminalAction.PRINT_HELP -> HELP_COMMAND
                }
                is TranslationResult.UnsupportedCommandError -> return Rewrite.Refused(
                    buildString {
                        append(result.offendingToken).append(": ").append(result.diagnosticExplanation)
                        result.suggestedEquivalent?.let { append(" Try: ").append(it) }
                    }
                )
            }
        }
        return Rewrite.Text(out.joinToString("\n"))
    }

    companion object {
        /** `help` prints the supported CMD subset through the shell, like any other command. */
        const val HELP_COMMAND = "printf '%s\\n' 'CMD translation: cd chdir dir type copy xcopy move ren " +
            "del erase rd rmdir md mkdir findstr where ipconfig tasklist taskkill echo set ver cls exit help'"

        /** Parses `NAME=value` entries as produced by ShellEnvironmentClient.buildEnvironment. */
        fun parseEnvironment(entries: Array<String>): Map<String, String> =
            entries.mapNotNull { entry ->
                val split = entry.indexOf('=')
                if (split <= 0) null else entry.substring(0, split) to entry.substring(split + 1)
            }.toMap()
    }
}
