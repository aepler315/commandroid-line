// SPDX-License-Identifier: GPL-3.0-only
package com.neoterm.input

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.annotation.UiThreadTest
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@UiThreadTest
class CompositionInstrumentationTest : ImeContract() {
    override fun context(): Context = InstrumentationRegistry.getInstrumentation().targetContext
}
