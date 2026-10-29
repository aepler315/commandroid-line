// SPDX-License-Identifier: GPL-3.0-only
package com.neoterm.input

import android.content.Context
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class CompositionEditTextTest : ImeContract() {
    override fun context(): Context = RuntimeEnvironment.application
}
