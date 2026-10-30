// SPDX-License-Identifier: GPL-3.0-only
package com.aepler.commandroid.ui.neoterm

import com.aepler.commandroid.presentation.TerminalLineKind

/**
 * One visual row of the terminal grid. [chars] is sized for the grid width (two UTF-16 units per
 * column so surrogate pairs always fit) and is reused when the owning ring-buffer slot is recycled.
 */
class TerminalRow internal constructor(columns: Int) {
    /** UTF-16 contents; only `[0, length)` is meaningful. */
    var chars: CharArray = CharArray(capacityFor(columns))
        private set

    /** Number of UTF-16 units used in [chars]. */
    var length: Int = 0
        private set

    /** Number of grid cells occupied (wide glyphs count as two, combining marks as zero). */
    var cells: Int = 0
        private set

    var kind: TerminalLineKind = TerminalLineKind.STDOUT
        internal set

    /** True when this row was soft-wrapped and its logical line continues on the next row. */
    var wrapped: Boolean = false
        internal set

    /** Allocates a new string; intended for tests and accessibility, never for the draw loop. */
    fun text(): String = String(chars, 0, length)

    internal fun reset(kind: TerminalLineKind) {
        length = 0
        cells = 0
        wrapped = false
        this.kind = kind
    }

    internal fun put(high: Char, low: Char?, width: Int) {
        val needed = length + if (low == null) 1 else 2
        // Only zero-width sequences (combining marks, ZWJ emoji) can exceed the per-column budget.
        if (needed > chars.size) chars = chars.copyOf(maxOf(needed, chars.size * 2))
        chars[length++] = high
        if (low != null) chars[length++] = low
        cells += width
    }

    internal companion object {
        fun capacityFor(columns: Int): Int = maxOf(1, columns) * 2
    }
}

/**
 * Fixed-capacity ring buffer of [TerminalRow]s. Appending is O(1) per produced row; once the ring
 * is full the oldest row object is recycled, so steady-state streaming allocates nothing.
 * Logical lines longer than [columns] cells soft-wrap; [resize] re-wraps on a width change.
 *
 * Not thread-safe: owned and mutated by the UI thread.
 */
class TerminalRowBuffer(
    columns: Int = DEFAULT_COLUMNS,
    val maxScrollbackLines: Int = MAX_SCROLLBACK_LINES
) {
    init {
        require(maxScrollbackLines > 0) { "maxScrollbackLines must be positive" }
    }

    var columns: Int = maxOf(1, columns)
        private set

    private var slots: Array<TerminalRow?> = arrayOfNulls(maxScrollbackLines)
    private var head = 0

    /** Number of rows currently held, at most [maxScrollbackLines]. */
    var size: Int = 0
        private set

    /** Total rows evicted from the top since creation; lets the view keep a scrolled position stable. */
    var evictedRows: Long = 0
        private set

    operator fun get(index: Int): TerminalRow {
        if (index < 0 || index >= size) throw IndexOutOfBoundsException("row $index of $size")
        return slots[(head + index) % maxScrollbackLines]!!
    }

    fun clear() {
        head = 0
        size = 0
    }

    /**
     * Appends one logical line (embedded '\n' starts further logical lines), wrapping at [columns].
     * Returns the number of rows produced.
     */
    fun appendLine(text: CharSequence, kind: TerminalLineKind = TerminalLineKind.STDOUT): Int {
        var produced = 1
        var row = nextRow(kind)
        var i = 0
        val end = text.length
        while (i < end) {
            val c = text[i]
            when {
                c == '\n' -> {
                    row = nextRow(kind); produced++; i++
                }
                c == '\t' -> {
                    var spaces = TAB_WIDTH - row.cells % TAB_WIDTH
                    while (spaces-- > 0) {
                        if (row.cells + 1 > columns) {
                            row.wrapped = true; row = nextRow(kind); produced++
                            break
                        }
                        row.put(' ', null, 1)
                    }
                    i++
                }
                c.isHighSurrogate() && i + 1 < end && text[i + 1].isLowSurrogate() -> {
                    val low = text[i + 1]
                    val width = cellWidth(Character.toCodePoint(c, low))
                    if (width > 0 && row.cells + width > columns && row.cells > 0) {
                        row.wrapped = true; row = nextRow(kind); produced++
                    }
                    row.put(c, low, width)
                    i += 2
                }
                c.isSurrogate() -> {
                    // Unpaired surrogate: render a replacement glyph rather than corrupt the row.
                    if (row.cells + 1 > columns) {
                        row.wrapped = true; row = nextRow(kind); produced++
                    }
                    row.put(REPLACEMENT, null, 1)
                    i++
                }
                c < ' ' || c == '\u007f' -> i++ // CR and other C0 controls are not drawable.
                else -> {
                    val width = cellWidth(c.code)
                    if (width > 0 && row.cells + width > columns && row.cells > 0) {
                        row.wrapped = true; row = nextRow(kind); produced++
                    }
                    row.put(c, null, width)
                    i++
                }
            }
        }
        return produced
    }

    /** Re-wraps every held logical line for [newColumns], reallocating row storage for the new width. */
    fun resize(newColumns: Int) {
        val target = maxOf(1, newColumns)
        if (target == columns) return
        val logical = ArrayList<Pair<String, TerminalLineKind>>()
        val builder = StringBuilder()
        var kind = TerminalLineKind.STDOUT
        var open = false
        for (index in 0 until size) {
            val row = this[index]
            if (!open) {
                builder.setLength(0); kind = row.kind; open = true
            }
            builder.append(row.chars, 0, row.length)
            if (!row.wrapped) {
                logical.add(builder.toString() to kind); open = false
            }
        }
        if (open) logical.add(builder.toString() to kind)
        columns = target
        slots = arrayOfNulls(maxScrollbackLines)
        head = 0
        size = 0
        for ((text, lineKind) in logical) appendLine(text, lineKind)
    }

    private fun nextRow(kind: TerminalLineKind): TerminalRow {
        val slotIndex: Int
        if (size < maxScrollbackLines) {
            slotIndex = (head + size) % maxScrollbackLines
            size++
        } else {
            slotIndex = head
            head = (head + 1) % maxScrollbackLines
            evictedRows++
        }
        var row = slots[slotIndex]
        if (row == null || row.chars.size < TerminalRow.capacityFor(columns)) {
            row = TerminalRow(columns)
            slots[slotIndex] = row
        }
        row.reset(kind)
        return row
    }

    companion object {
        const val MAX_SCROLLBACK_LINES = 5000
        const val DEFAULT_COLUMNS = 80
        private const val TAB_WIDTH = 8
        private const val REPLACEMENT = '�'

        /** Grid cells for [codePoint]: 0 for combining/format marks, 2 for East Asian wide and emoji. */
        fun cellWidth(codePoint: Int): Int {
            if (codePoint == 0x200D || codePoint in 0xFE00..0xFE0F || codePoint in 0xE0100..0xE01EF) return 0
            when (Character.getType(codePoint)) {
                Character.NON_SPACING_MARK.toInt(),
                Character.ENCLOSING_MARK.toInt(),
                Character.FORMAT.toInt() -> return 0
            }
            return if (isWide(codePoint)) 2 else 1
        }

        private fun isWide(cp: Int): Boolean =
            cp in 0x1100..0x115F || cp in 0x2E80..0x303E || cp in 0x3041..0x33FF ||
                cp in 0x3400..0x4DBF || cp in 0x4E00..0x9FFF || cp in 0xA000..0xA4CF ||
                cp in 0xAC00..0xD7A3 || cp in 0xF900..0xFAFF || cp in 0xFE30..0xFE4F ||
                cp in 0xFF00..0xFF60 || cp in 0xFFE0..0xFFE6 || cp in 0x1F200..0x1F251 ||
                cp in 0x1F300..0x1F64F || cp in 0x1F680..0x1F6FF || cp in 0x1F7E0..0x1F7EB ||
                cp in 0x1F900..0x1F9FF || cp in 0x1FA70..0x1FAFF || cp in 0x20000..0x3FFFD
    }
}
