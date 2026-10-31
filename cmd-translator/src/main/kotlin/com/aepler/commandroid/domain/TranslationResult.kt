// SPDX-License-Identifier: GPL-3.0-only
package com.aepler.commandroid.domain

import java.io.File

sealed interface TranslationResult {
    /**
     * Command successfully mapped from Windows CMD/PowerShell syntax to Android POSIX shell.
     */
    data class Translated(
        val originalCommand: String,
        val posixCommand: String,
        val explanation: String?,
        val updatedWorkingDirectory: File? = null
    ) : TranslationResult

    /**
     * Built-in terminal action handled directly by the MVI reducer (e.g., `cls`, `exit`).
     */
    data class BuiltInAction(val action: BuiltInTerminalAction) : TranslationResult

    /**
     * Command is unrecognized or relies on unsupported Win32 subsystems (e.g., `regedit`, `wmic`).
     * Returns an actionable diagnostic explanation instead of raw `sh: not found`.
     */
    data class UnsupportedCommandError(
        val offendingToken: String,
        val diagnosticExplanation: String,
        val suggestedEquivalent: String?
    ) : TranslationResult
}

enum class BuiltInTerminalAction { CLEAR_SCREEN, EXIT_SESSION, PRINT_HELP }
