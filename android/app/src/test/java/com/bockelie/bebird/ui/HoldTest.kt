// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The press-and-hold rules behind Disconnect and Quit (#38). */
class HoldTest {
    private val hold = Hold(durationMs = 2_000)

    @Test fun eachButtonHasItsOwnDuration() {
        assertEquals(2_000L, Hold.DISCONNECT_MS)
        assertEquals(5_000L, Hold.QUIT_MS)
        val quit = Hold(Hold.QUIT_MS)
        quit.press(0)
        hold.press(0)
        assertTrue(hold.complete(2_000))
        assertFalse(quit.complete(2_000))
        assertEquals(0.4f, quit.progress(2_000), 1e-6f)
        assertTrue(quit.complete(5_000))
    }

    @Test fun theFillRunsFromNothingToFullOverTheHold() {
        assertEquals(0f, hold.progress(500), 0f)  // not held
        hold.press(1_000)
        assertEquals(0f, hold.progress(1_000), 0f)
        assertEquals(0.25f, hold.progress(1_500), 1e-6f)
        assertEquals(0.5f, hold.progress(2_000), 1e-6f)
        assertEquals(1f, hold.progress(9_000), 0f)  // never past full
    }

    @Test fun completesExactlyOnceAndNotAgainWhileStillHeld() {
        hold.press(0)
        assertFalse(hold.complete(1_999))
        assertTrue(hold.complete(2_000))
        assertFalse(hold.complete(2_016))
        assertFalse(hold.complete(60_000))
        assertEquals(1f, hold.progress(2_016), 0f)
        assertEquals(Hold.Release.DONE, hold.release(60_000))
    }

    @Test fun isDoneOnlyBetweenCompletionAndLettingGo() {
        // what keeps the completion's vibration from being cut off (#44)
        assertFalse(hold.isDone)
        hold.press(0)
        assertFalse(hold.isDone)
        hold.complete(2_000)
        assertTrue(hold.isDone)
        hold.release(2_100)
        assertFalse(hold.isDone)
        hold.press(3_000)
        hold.complete(5_000)
        hold.cancel()
        assertFalse(hold.isDone)
    }

    @Test fun letGoEarlyCancels() {
        hold.press(0)
        assertEquals(Hold.Release.CANCELLED, hold.release(1_500))
        assertFalse(hold.isHeld)
        assertEquals(0f, hold.progress(1_600), 0f)
        assertFalse(hold.complete(2_000))  // the old press can't complete later
    }

    @Test fun aQuickTapIsATapForTheHint() {
        hold.press(0)
        assertEquals(Hold.Release.TAP, hold.release(Hold.TAP_MS - 1))
        hold.press(1_000)
        assertEquals(Hold.Release.CANCELLED, hold.release(1_000 + Hold.TAP_MS))
    }

    @Test fun aCancelledGestureDoesNothing() {
        // slid off the button, or another gesture took the pointer
        hold.press(0)
        hold.cancel()
        assertFalse(hold.isHeld)
        assertFalse(hold.complete(5_000))
        assertNull(hold.release(5_000))
    }

    @Test fun aNewPressStartsAfresh() {
        hold.press(0)
        assertTrue(hold.complete(2_000))
        hold.release(2_100)
        assertTrue(hold.press(3_000))
        assertEquals(0f, hold.progress(3_000), 0f)
        assertFalse(hold.complete(4_999))
        assertTrue(hold.complete(5_000))
    }

    @Test fun aSecondPressWhileHeldChangesNothing() {
        assertTrue(hold.press(0))
        assertFalse(hold.press(1_000))
        assertTrue(hold.complete(2_000))
    }
}
