// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.connection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalLaunchTest {
    private val cap = 120_000L
    private val e = ExternalLaunch(cap)

    @Test fun anOrdinaryStopIsNotCovered() {
        assertFalse(e.coversStop(1_000))
    }

    @Test fun ourLaunchCoversTheStopUntilTheCap() {
        e.begin(1_000)
        assertTrue(e.coversStop(1_500))
        assertTrue(e.coversStop(1_000 + cap - 1))
        assertFalse(e.coversStop(1_000 + cap))  // a stop after the cap is an ordinary one
    }

    @Test fun returningClearsIt() {
        e.begin(1_000)
        e.returned()
        assertFalse(e.coversStop(1_500))
    }

    @Test fun aNewLaunchRestartsTheCap() {
        e.begin(0)
        e.returned()
        e.begin(100_000)
        assertFalse(e.coversStop(100_000 + cap))
        assertTrue(e.coversStop(100_000 + cap - 1))
    }
}
