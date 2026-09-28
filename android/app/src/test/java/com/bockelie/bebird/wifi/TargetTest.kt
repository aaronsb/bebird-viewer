// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.wifi

import com.bockelie.bebird.wifi.ScopeWifi.Target
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TargetTest {
    @Test fun anExactRequestWithABssidFallsBackToTheSsidOnce() {
        val first = Target.Exact("bebird-ES-1", "12:34:56:78:9A:9F")
        val second = first.fallback()
        // it carries the BSSID under test, to be rejected only if this SSID-only request joins
        assertEquals(Target.Exact("bebird-ES-1", null, suspect = "12:34:56:78:9A:9F"), second)
        assertNull(second?.fallback())  // no third try: the fallback can't loop
    }

    @Test fun thePickerHasNoFallback() {
        assertNull(Target.AnyScope.fallback())
    }
}
