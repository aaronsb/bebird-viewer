// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.control

import org.junit.Assert.assertEquals
import org.junit.Test

class RollFilterTest {
    @Test fun changesUnderThreeDegreesAreIgnored() {
        val f = RollFilter()
        assertEquals(0, f.update(2))
        assertEquals(0, f.update(358))  // 2° the other way round
        assertEquals(3, f.update(3))
        assertEquals(3, f.update(5))
        assertEquals(6, f.update(6))
    }

    @Test fun theDeadbandWrapsAroundZero() {
        val f = RollFilter()
        f.update(10)
        assertEquals(10, f.update(10))
        assertEquals(359, f.update(359))  // 11° away, the short way
        assertEquals(359, f.update(1))    // 2° away across 0
        assertEquals(2, f.update(2))      // 3° away across 0
    }

    @Test fun rotationAddsTrimAndRollWhenAuto() {
        assertEquals(47, RollFilter.rotation(shown = 47, autoRotate = true, trim = 0))
        assertEquals(15, RollFilter.rotation(shown = 47, autoRotate = false, trim = 15))
        assertEquals(2, RollFilter.rotation(shown = 350, autoRotate = true, trim = 12))
        assertEquals(345, RollFilter.rotation(shown = 0, autoRotate = true, trim = -15))
    }

    @Test fun trimStepsAndLimits() {
        assertEquals(15, RollFilter.stepTrim(0, 1))
        assertEquals(-15, RollFilter.stepTrim(0, -1))
        assertEquals(180, RollFilter.stepTrim(180, 1))
        assertEquals(-180, RollFilter.stepTrim(-170, -1))
    }
}
