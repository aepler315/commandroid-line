// SPDX-License-Identifier: GPL-3.0-only
package com.aepler.commandroid.ui.neoterm

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import com.termux.R

/** Keys offered by [ModifierKeyBar]. */
enum class ModifierKey(val label: String, @StringRes val description: Int, val isModifier: Boolean = false) {
    CTRL("CTRL", R.string.commandroid_key_ctrl, isModifier = true),
    ALT("ALT", R.string.commandroid_key_alt, isModifier = true),
    TAB("TAB", R.string.commandroid_key_tab),
    ESC("ESC", R.string.commandroid_key_esc),
    UP("↑", R.string.commandroid_key_up),
    DOWN("↓", R.string.commandroid_key_down),
    LEFT("←", R.string.commandroid_key_left),
    RIGHT("→", R.string.commandroid_key_right)
}

/**
 * Dockable strip of terminal keys shown above the soft keyboard (plain Views, no Compose).
 * CTRL and ALT are sticky: a tap arms them and the next non-modifier key consumes them.
 * Every key is at least 56dp x 48dp (spec: >= 56dp x 44dp and touch targets >= 48dp).
 */
class ModifierKeyBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : HorizontalScrollView(context, attrs) {

    /** Receives every key press with the modifier state that applies to it. */
    fun interface Listener {
        fun onModifierKey(key: ModifierKey, ctrl: Boolean, alt: Boolean)
    }

    var listener: Listener? = null

    var isCtrlActive: Boolean = false
        private set
    var isAltActive: Boolean = false
        private set

    private val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private val keyViews = LinkedHashMap<ModifierKey, TextView>()

    init {
        isFillViewport = true
        isHorizontalScrollBarEnabled = false
        setBackgroundColor(context.getColor(R.color.commandroid_modifier_bar_background))
        val minWidth = resources.getDimensionPixelSize(R.dimen.commandroid_modifier_key_min_width)
        val minHeight = resources.getDimensionPixelSize(R.dimen.commandroid_modifier_key_min_height)
        val margin = resources.getDimensionPixelSize(R.dimen.commandroid_modifier_key_margin)
        val textSize = resources.getDimension(R.dimen.commandroid_modifier_key_text_size)
        for (key in ModifierKey.values()) {
            val view = TextView(context).apply {
                text = key.label
                contentDescription = context.getString(key.description)
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize)
                setTextColor(context.getColor(R.color.commandroid_modifier_key_text))
                this.minWidth = minWidth
                this.minHeight = minHeight
                minimumWidth = minWidth
                minimumHeight = minHeight
                isClickable = true
                isFocusable = false // Never steal focus (and the IME connection) from the terminal.
                setOnClickListener { press(key) }
            }
            keyViews[key] = view
            row.addView(view, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(margin, margin, margin, margin)
            })
        }
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        refreshKeyStates()
    }

    /** The button for [key]. */
    fun keyView(key: ModifierKey): View = keyViews.getValue(key)

    /** Handles a key tap as if its button had been pressed. */
    fun press(key: ModifierKey) {
        when (key) {
            ModifierKey.CTRL -> isCtrlActive = !isCtrlActive
            ModifierKey.ALT -> isAltActive = !isAltActive
            else -> Unit
        }
        val ctrl = isCtrlActive
        val alt = isAltActive
        if (!key.isModifier) {
            isCtrlActive = false
            isAltActive = false
        }
        refreshKeyStates()
        listener?.onModifierKey(key, ctrl, alt)
    }

    /**
     * Returns and clears the armed modifiers, for callers applying them to a soft-keyboard key
     * instead of a bar key. Index 0 = ctrl, 1 = alt.
     */
    fun consumeModifiers(): BooleanArray {
        val state = booleanArrayOf(isCtrlActive, isAltActive)
        if (isCtrlActive || isAltActive) {
            isCtrlActive = false
            isAltActive = false
            refreshKeyStates()
        }
        return state
    }

    private fun refreshKeyStates() {
        for ((key, view) in keyViews) {
            val active = (key == ModifierKey.CTRL && isCtrlActive) || (key == ModifierKey.ALT && isAltActive)
            view.isActivated = active
            view.isSelected = active
            view.setBackgroundColor(
                context.getColor(
                    if (active) R.color.commandroid_modifier_key_active else R.color.commandroid_modifier_key_background
                )
            )
        }
    }

    companion object {
        /** Android key code for [key]. */
        fun keyCodeFor(key: ModifierKey): Int = when (key) {
            ModifierKey.CTRL -> KeyEvent.KEYCODE_CTRL_LEFT
            ModifierKey.ALT -> KeyEvent.KEYCODE_ALT_LEFT
            ModifierKey.TAB -> KeyEvent.KEYCODE_TAB
            ModifierKey.ESC -> KeyEvent.KEYCODE_ESCAPE
            ModifierKey.UP -> KeyEvent.KEYCODE_DPAD_UP
            ModifierKey.DOWN -> KeyEvent.KEYCODE_DPAD_DOWN
            ModifierKey.LEFT -> KeyEvent.KEYCODE_DPAD_LEFT
            ModifierKey.RIGHT -> KeyEvent.KEYCODE_DPAD_RIGHT
        }

        /** Meta state flags for the given modifier combination. */
        fun metaStateFor(ctrl: Boolean, alt: Boolean): Int {
            var meta = 0
            if (ctrl) meta = meta or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
            if (alt) meta = meta or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
            return meta
        }

        /** Builds a [KeyEvent] for [key] carrying the ctrl/alt meta state. */
        fun keyEventFor(
            key: ModifierKey,
            ctrl: Boolean,
            alt: Boolean,
            action: Int = KeyEvent.ACTION_DOWN,
            eventTime: Long = android.os.SystemClock.uptimeMillis()
        ): KeyEvent = KeyEvent(eventTime, eventTime, action, keyCodeFor(key), 0, metaStateFor(ctrl, alt))

        /** Down and up events for [key], in dispatch order. */
        fun keyEventsFor(key: ModifierKey, ctrl: Boolean, alt: Boolean): Array<KeyEvent> {
            val now = android.os.SystemClock.uptimeMillis()
            return arrayOf(
                keyEventFor(key, ctrl, alt, KeyEvent.ACTION_DOWN, now),
                keyEventFor(key, ctrl, alt, KeyEvent.ACTION_UP, now)
            )
        }
    }
}
