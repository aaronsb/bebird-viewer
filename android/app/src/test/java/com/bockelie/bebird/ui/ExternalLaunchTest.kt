// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalLaunchTest {
    private val cap = 120_000L
    private val e = ExternalLaunch(cap)

    @Test fun anOrdinaryStopIsNotCovered() {
        assertFalse(e.coversStop(1_000))
        assertFalse(e.expired(10_000_000))
        assertNull(e.deadline())
    }

    @Test fun ourLaunchCoversTheStopUntilTheCap() {
        e.begin(1_000)
        assertTrue(e.coversStop(1_500))
        assertEquals(1_000 + cap, e.deadline())
        assertFalse(e.expired(1_000 + cap - 1))
        assertTrue(e.expired(1_000 + cap))
        assertFalse(e.coversStop(1_000 + cap))  // a stop after the cap disconnects at once
    }

    @Test fun returningClearsIt() {
        e.begin(1_000)
        e.returned()
        assertFalse(e.coversStop(1_500))
        assertFalse(e.expired(1_000 + cap))  // a late timer after returning does nothing
        assertNull(e.deadline())
    }

    @Test fun aNewLaunchRestartsTheCap() {
        e.begin(0)
        e.returned()
        e.begin(100_000)
        assertFalse(e.expired(cap))
        assertTrue(e.expired(100_000 + cap))
    }
}
