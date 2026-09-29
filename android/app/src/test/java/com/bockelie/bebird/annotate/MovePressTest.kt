// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.annotate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MovePressTest {
    private val slop = 18f
    private val timeout = 400L

    private fun press(
        present: Boolean = true, pressed: Boolean = true, consumed: Boolean = false, distance: Float = 0f, elapsed: Long = 50,
    ) = classifyMovePress(present, pressed, consumed, distance, slop, elapsed, timeout)

    @Test fun stillAndDownIsUndecidedUntilTheTimeout() {
        assertNull(press())
        assertNull(press(distance = slop, elapsed = timeout - 1))  // at the slop is not past it
        assertEquals(PressOutcome.DELETE, press(elapsed = timeout))
        assertEquals(PressOutcome.DELETE, press(distance = 5f, elapsed = timeout + 30))
    }

    @Test fun pastTheSlopBeforeTheTimeoutMoves() {
        assertEquals(PressOutcome.MOVE, press(distance = slop + 0.5f, elapsed = 10))
        // moving wins over a late event: it still hasn't been held still
        assertEquals(PressOutcome.MOVE, press(distance = 40f, elapsed = timeout + 5))
    }

    @Test fun liftingOrLosingTheFingerDoesNothing() {
        assertEquals(PressOutcome.NONE, press(pressed = false))
        assertEquals(PressOutcome.NONE, press(pressed = false, distance = 50f))  // a flick that lifted
        assertEquals(PressOutcome.NONE, press(present = false))
        assertEquals(PressOutcome.NONE, press(consumed = true, distance = 50f))
        assertEquals(PressOutcome.NONE, press(pressed = false, elapsed = timeout + 100))
    }
}
