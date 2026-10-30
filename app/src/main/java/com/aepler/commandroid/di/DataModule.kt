// SPDX-License-Identifier: GPL-3.0-only
package com.aepler.commandroid.di

import com.aepler.commandroid.data.process.ShellConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/** App-lifetime scope for shell I/O; survives activities and configuration changes. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ProcessScope

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides
    @Singleton
    fun shellConfig(): ShellConfig = ShellConfig()

    @Provides
    @Singleton
    @ProcessScope
    fun processScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
