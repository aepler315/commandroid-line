// SPDX-License-Identifier: GPL-3.0-only
package com.aepler.commandroid.domain

import java.io.File
import javax.inject.Inject

/** STUB: implemented by the domain workstream. */
class DefaultWindowsToLinuxTranslator @Inject constructor() : WindowsToLinuxTranslator {
    override fun translate(
        rawCommand: String,
        currentDirectory: File,
        environment: Map<String, String>
    ): TranslationResult = TranslationResult.Translated(rawCommand, rawCommand, null)
}
