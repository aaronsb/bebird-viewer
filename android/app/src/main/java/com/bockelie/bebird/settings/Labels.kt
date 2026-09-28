// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.settings

import java.text.BreakIterator

object Labels {
    const val MAX_CODE_POINTS = 60

    /**
     * [text] cut to at most [max] code points, only between user-perceived characters, so an
     * emoji (surrogate pair, ZWJ sequence, flag) or an accented letter is never split.
     */
    fun limit(text: String, max: Int = MAX_CODE_POINTS): String {
        if (text.codePointCount(0, text.length) <= max) return text
        val it = BreakIterator.getCharacterInstance().apply { setText(text) }
        var end = 0
        var next = it.next()
        while (next != BreakIterator.DONE && text.codePointCount(0, next) <= max) {
            if (!joined(text, next)) end = next
            next = it.next()
        }
        return text.substring(0, end)
    }

    /**
     * Whether [at] is inside an emoji sequence that an older BreakIterator (the JVM's before
     * JDK 20) splits: next to a zero-width joiner, before a variation selector or a skin-tone
     * modifier. Android's ICU-based iterator already keeps these together.
     */
    private fun joined(text: String, at: Int): Boolean {
        if (at <= 0 || at >= text.length) return false
        val before = text.codePointBefore(at)
        val after = text.codePointAt(at)
        return before == ZWJ || after == ZWJ || after in 0xFE00..0xFE0F || after in 0x1F3FB..0x1F3FF
    }

    private const val ZWJ = 0x200D
}
