// SPDX-License-Identifier: GPL-3.0-only
package com.aepler.commandroid.domain

import java.io.File

/**
 * Validates and translates one committed command line.
 *
 * Blank input yields an empty [TranslationResult.Translated] (nothing to run). Over-long lines and
 * lines containing control characters (other than tab) are rejected with a diagnostic; the input
 * text itself is never logged.
 */
class TranslateCommandUseCase(
    private val translator: WindowsToLinuxTranslator
) {
    operator fun invoke(
        rawCommand: String,
        currentDirectory: File,
        environment: Map<String, String>
    ): TranslationResult {
        val command = rawCommand.trim()
        if (command.isEmpty()) return TranslationResult.Translated("", "", null)
        if (command.length > MAX_COMMAND_LENGTH) {
            return TranslationResult.UnsupportedCommandError(
                offendingToken = command.substringBefore(' ').take(MAX_TOKEN_PREVIEW),
                diagnosticExplanation = "Command is ${command.length} characters long; the limit is " +
                    "$MAX_COMMAND_LENGTH. Split it into shorter commands or use a script file.",
                suggestedEquivalent = null
            )
        }
        val control = command.firstOrNull { it != '\t' && Character.isISOControl(it) }
        if (control != null) {
            val token = "U+%04X".format(control.code)
            return TranslationResult.UnsupportedCommandError(
                offendingToken = token,
                diagnosticExplanation = "Command contains the control character $token " +
                    "(e.g. an embedded newline); submit one command line at a time.",
                suggestedEquivalent = null
            )
        }
        return translator.translate(command, currentDirectory, environment)
    }

    companion object {
        const val MAX_COMMAND_LENGTH = 4096
        private const val MAX_TOKEN_PREVIEW = 32
    }
}
