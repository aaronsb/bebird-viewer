// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.capture

/**
 * Presentation times for the encoder from the wall clock, since the scope's frame rate varies
 * (about 10 fps): microseconds since the first frame, always increasing.
 */
class PtsClock {
    private var start = -1L
    private var last = -1L

    /** The presentation time in µs for a frame that arrived at [nanos] (any monotonic clock). */
    fun ptsUs(nanos: Long): Long {
        if (start < 0) start = nanos
        val us = maxOf((nanos - start) / 1000, last + 1)  // never repeat or go back
        last = us
        return us
    }
}
