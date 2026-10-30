// SPDX-License-Identifier: GPL-3.0-only
package com.aepler.commandroid.domain

import java.io.File
import javax.inject.Inject

/** Validates and translates one committed command line. STUB: completed by the domain workstream. */
class TranslateCommandUseCase @Inject constructor(
    private val translator: WindowsToLinuxTranslator
) {
    operator fun invoke(
        rawCommand: String,
        currentDirectory: File,
        environment: Map<String, String>
    ): TranslationResult = translator.translate(rawCommand, currentDirectory, environment)
}
