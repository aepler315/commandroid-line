// SPDX-License-Identifier: GPL-3.0-only
package com.aepler.commandroid.domain

import java.io.File

interface WindowsToLinuxTranslator {
    fun translate(
        rawCommand: String,
        currentDirectory: File,
        environment: Map<String, String>
    ): TranslationResult
}
