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
 * POSIX command before pressing Enter; nothing here runs a process. A multi-line draft is
 * translated as one CMD chain, its non-blank lines joined with `&` (unconditional sequencing,
 * which is what separate lines mean), so a `cd` on one line is only a possible directory for the
 * lines after it and the translator's deletion guard checks both. The result is a single POSIX
 * line. [workingDirectory] is a snapshot of the terminal's foreground process (null when unknown,
 * which refuses translation) and [environment] is the app's view of the session environment;
 * the shell may since have changed either.
 */
class CmdTransmitTransform(
    private val workingDirectory: () -> File?,
    private val environment: (cwd: File) -> Map<String, String>,
    private val translate: TranslateCommandUseCase = TranslateCommandUseCase(DefaultWindowsToLinuxTranslator()),
) : TransmitTransform {

    override fun rewrite(draft: String): Rewrite {
        val lines = draft.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return Rewrite.Text("")
        val command = if (lines.size == 1) lines[0] else {
            lines.forEachIndexed { index, line ->
                joinHazard(line)?.let { return Rewrite.Refused("line ${index + 1} $it") }
            }
            val joined = lines.joinToString(" & ")
            if (joined.length > TranslateCommandUseCase.MAX_COMMAND_LENGTH) {
                return Rewrite.Refused("the ${lines.size} lines are translated as one CMD command chain of " +
                    "${joined.length} characters; the limit is ${TranslateCommandUseCase.MAX_COMMAND_LENGTH}. " +
                    "Transmit fewer lines at a time.")
            }
            joined
        }
        val cwd = workingDirectory() ?: return Rewrite.Refused(UNKNOWN_DIRECTORY)
        return when (val result = translate(command, cwd, environment(cwd))) {
            // Nothing in a multi-line draft is CMD: send it as typed (the `&` join is CMD syntax).
            is TranslationResult.Translated ->
                Rewrite.Text(if (lines.size > 1 && result.posixCommand == command) draft else result.posixCommand)
            is TranslationResult.BuiltInAction -> Rewrite.Text(when (result.action) {
                BuiltInTerminalAction.CLEAR_SCREEN -> "clear"
                BuiltInTerminalAction.EXIT_SESSION -> "exit"
                BuiltInTerminalAction.PRINT_HELP -> HELP_COMMAND
            })
            is TranslationResult.UnsupportedCommandError -> Rewrite.Refused(
                buildString {
                    append(result.offendingToken).append(": ").append(result.diagnosticExplanation)
                    result.suggestedEquivalent?.let { append(" Try: ").append(it) }
                }
            )
        }
    }

    companion object {
        /** `help` prints the supported CMD subset through the shell, like any other command. */
        const val HELP_COMMAND = "printf '%s\\n' 'CMD translation: cd chdir dir type copy xcopy move ren " +
            "del erase rd rmdir md mkdir findstr where ipconfig tasklist taskkill echo set ver cls exit help'"

        const val UNKNOWN_DIRECTORY = "cannot determine the shell's working directory, so CMD paths " +
            "cannot be checked. Turn CMD off or try again once the session is running."

        private val WHITESPACE = Regex("\\s+")

        /**
         * Why joining [line] (trimmed) to the next with `&` would change how CMD parses it, or null.
         * Mirrors CmdLexer: quotes group text and `^` escapes the next character outside quotes.
         */
        internal fun joinHazard(line: String): String? {
            var inQuotes = false
            var i = 0
            while (i < line.length) {
                val c = line[i]
                if (inQuotes) {
                    if (c == '"') inQuotes = false
                } else if (c == '"') {
                    inQuotes = true
                } else if (c == '^') {
                    if (i == line.lastIndex) {
                        return "ends with ^ (CMD line continuation); multi-line CMD drafts are translated " +
                            "as one command chain. Join the lines yourself or transmit them separately."
                    }
                    i++
                }
                i++
            }
            return if (!inQuotes) null else "has an unmatched double quote; multi-line CMD drafts are " +
                "translated as one command chain. Close the quote or transmit the lines separately."
        }

        /**
         * The terminal foreground process group (`tpgid`, field 8) from a `/proc/<pid>/stat` line, or
         * null when malformed. The command name (field 2) is parenthesised and may itself contain
         * spaces and parentheses, so fields are counted after its last `)`.
         */
        fun parseForegroundProcessGroup(stat: String): Int? {
            val close = stat.lastIndexOf(')')
            if (close < 0) return null
            // After comm: state(3) ppid(4) pgrp(5) session(6) tty_nr(7) tpgid(8).
            return stat.substring(close + 1).trim().split(WHITESPACE).getOrNull(5)?.toIntOrNull()
        }

        /**
         * The working directory of the terminal's foreground process group leader (e.g. a nested
         * shell started from the session's shell) when it can be read, else [shellCwd] (the session's
         * own shell), else null. [readStat] and [readCwd] read `/proc/<pid>/stat` and resolve
         * `/proc/<pid>/cwd`, returning null on any failure.
         */
        fun foregroundWorkingDirectory(
            shellPid: Int,
            shellCwd: () -> String?,
            readStat: (pid: Int) -> String?,
            readCwd: (pid: Int) -> String?,
        ): File? {
            if (shellPid > 0) {
                val group = readStat(shellPid)?.let(::parseForegroundProcessGroup)
                if (group != null && group > 0) readCwd(group)?.let { return File(it) }
            }
            return shellCwd()?.let(::File)
        }

        /** Parses `NAME=value` entries as produced by ShellEnvironmentClient.buildEnvironment. */
        fun parseEnvironment(entries: Array<String>): Map<String, String> =
            entries.mapNotNull { entry ->
                val split = entry.indexOf('=')
                if (split <= 0) null else entry.substring(0, split) to entry.substring(split + 1)
            }.toMap()
    }
}
