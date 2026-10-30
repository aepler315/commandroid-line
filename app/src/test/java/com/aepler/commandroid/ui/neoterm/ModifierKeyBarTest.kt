// SPDX-License-Identifier: GPL-3.0-only
package com.aepler.commandroid.ui.neoterm

import android.app.Application
import android.view.KeyEvent
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ModifierKeyBarTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun everyKeyMeetsMinimumTouchTarget() {
        for (screenWidth in intArrayOf(320, 1440)) {
            val bar = ModifierKeyBar(context)
            bar.measure(
                View.MeasureSpec.makeMeasureSpec(screenWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            bar.layout(0, 0, bar.measuredWidth, bar.measuredHeight)
            val density = context.resources.displayMetrics.density
            for (key in ModifierKey.values()) {
                val view = bar.keyView(key)
                assertTrue("$key width ${view.width}", view.width >= 56 * density)
                assertTrue("$key height ${view.height}", view.height >= 44 * density)
                assertTrue("$key touch target ${view.height}", view.height >= 48 * density)
            }
        }
    }

    @Test fun ctrlAndAltAreStickyUntilNextKey() {
        val bar = ModifierKeyBar(context)
        val events = mutableListOf<Triple<ModifierKey, Boolean, Boolean>>()
        bar.listener = ModifierKeyBar.Listener { key, ctrl, alt -> events += Triple(key, ctrl, alt) }
        bar.keyView(ModifierKey.CTRL).performClick()
        assertTrue(bar.isCtrlActive)
        bar.keyView(ModifierKey.ALT).performClick()
        bar.keyView(ModifierKey.UP).performClick()
        assertFalse(bar.isCtrlActive)
        assertFalse(bar.isAltActive)
        assertEquals(Triple(ModifierKey.UP, true, true), events.last())
        bar.keyView(ModifierKey.TAB).performClick()
        assertEquals(Triple(ModifierKey.TAB, false, false), events.last())

        bar.press(ModifierKey.CTRL)
        bar.press(ModifierKey.CTRL) // Second tap disarms.
        assertFalse(bar.isCtrlActive)
    }

    @Test fun keyEventsCarryCodesAndMetaState() {
        val event = ModifierKeyBar.keyEventFor(ModifierKey.LEFT, ctrl = true, alt = false)
        assertEquals(KeyEvent.KEYCODE_DPAD_LEFT, event.keyCode)
        assertEquals(KeyEvent.ACTION_DOWN, event.action)
        assertTrue(event.isCtrlPressed)
        assertFalse(event.isAltPressed)
        assertEquals(KeyEvent.KEYCODE_ESCAPE, ModifierKeyBar.keyCodeFor(ModifierKey.ESC))
        assertEquals(KeyEvent.KEYCODE_TAB, ModifierKeyBar.keyCodeFor(ModifierKey.TAB))
        val pair = ModifierKeyBar.keyEventsFor(ModifierKey.ESC, ctrl = false, alt = true)
        assertEquals(KeyEvent.ACTION_UP, pair[1].action)
        assertTrue(pair[0].isAltPressed)
    }
}
