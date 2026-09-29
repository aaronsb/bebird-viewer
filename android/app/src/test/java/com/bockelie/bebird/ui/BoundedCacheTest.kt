// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BoundedCacheTest {
    @Test fun keepsAtMostMaxAndDropsTheLeastRecentlyUsed() {
        val c = BoundedCache<Int, String>(3)
        for (k in 1..3) c[k] = "v$k"
        assertEquals("v1", c[1])  // touch 1: now 2 is the eldest
        c[4] = "v4"
        assertEquals(3, c.size)
        assertNull(c[2])
        assertEquals("v1", c[1]); assertEquals("v3", c[3]); assertEquals("v4", c[4])
        repeat(100) { c[100 + it] = "x" }
        assertEquals(3, c.size)  // bounded, however many are added
    }

    @Test fun cachesAtAll() {
        // the bug it replaces: an outer `size` (the image size, e.g. 1260) shadowed the map's,
        // so a small cache evicted everything at once, and a large one never evicted
        val c = BoundedCache<String, Int>(6)
        c["ring"] = 1
        assertEquals(1, c["ring"])
    }
}
