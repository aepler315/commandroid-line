// SPDX-License-Identifier: GPL-3.0-only
package com.aepler.commandroid.di

import com.aepler.commandroid.data.process.DefaultProcessSessionRepository
import com.aepler.commandroid.data.process.ProcessSessionRepository
import com.aepler.commandroid.data.process.ShellTerminalProcessManager
import com.aepler.commandroid.data.process.TerminalProcessManager
import com.aepler.commandroid.domain.DefaultWindowsToLinuxTranslator
import com.aepler.commandroid.domain.WindowsToLinuxTranslator
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {
    @Binds abstract fun translator(impl: DefaultWindowsToLinuxTranslator): WindowsToLinuxTranslator
    @Binds abstract fun processManager(impl: ShellTerminalProcessManager): TerminalProcessManager
    @Binds abstract fun sessions(impl: DefaultProcessSessionRepository): ProcessSessionRepository
}
