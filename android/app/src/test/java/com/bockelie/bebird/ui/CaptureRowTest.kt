// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The capture rows (#54): Reconnect keeps its word only where it fits. */
class CaptureRowTest {
    @Test fun reconnectShowsItsWordOnlyWhereItFits() {
        assertTrue(reconnectShowsLabel(available = 300, label = 200, extras = 100))
        assertFalse(reconnectShowsLabel(available = 299, label = 200, extras = 100))
        assertTrue(reconnectShowsLabel(available = Int.MAX_VALUE, label = 200, extras = 100))  // before the first layout
    }
}
