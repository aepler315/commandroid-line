// SPDX-License-Identifier: GPL-3.0-only
package com.aepler.commandroid.presentation

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Inject

/** Supplies the starting directory and base environment for new terminal sessions. */
interface SessionEnvironmentProvider {
    /** Directory a freshly created session starts in. */
    fun defaultWorkingDirectory(): File

    /** Environment passed to translation and to every executed command. */
    fun environment(): Map<String, String>
}

/**
 * Starts sessions in shared storage when the app can list it, otherwise in the app's private
 * files directory so the shell always has a usable working directory.
 */
class AndroidSessionEnvironmentProvider @Inject constructor(
    @ApplicationContext private val context: Context
) : SessionEnvironmentProvider {

    override fun defaultWorkingDirectory(): File {
        val shared = File(WindowsPromptFormatter.STORAGE_ROOT)
        val accessible = runCatching { shared.isDirectory && shared.canRead() && shared.list() != null }
            .getOrDefault(false)
        return if (accessible) shared else context.filesDir
    }

    override fun environment(): Map<String, String> {
        val home = context.filesDir.absolutePath
        return buildMap {
            System.getenv("PATH")?.let { put("PATH", it) }
            put("HOME", home)
            put("TMPDIR", context.cacheDir.absolutePath)
            put("TERM", "dumb")
            put("EXTERNAL_STORAGE", WindowsPromptFormatter.STORAGE_ROOT)
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class PresentationModule {
    @Binds
    abstract fun sessionEnvironment(impl: AndroidSessionEnvironmentProvider): SessionEnvironmentProvider
}
