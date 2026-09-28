// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.scope

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoWatchdogTest {
    @Test fun retriesStartOnlyAfterTheTimeout() {
        val w = VideoWatchdog(startedAt = 1000)
        assertFalse(w.shouldRetryStart(1000 + VideoWatchdog.RETRY_MS))
        assertTrue(w.shouldRetryStart(1001 + VideoWatchdog.RETRY_MS))
        // the timer restarts from the retry
        assertFalse(w.shouldRetryStart(1002 + VideoWatchdog.RETRY_MS))
    }

    @Test fun neverRetriesOnceAFrameArrived() {
        val w = VideoWatchdog(startedAt = 0)
        w.onFrame(500)
        assertFalse(w.shouldRetryStart(60_000))
        assertEquals(0, w.retries)
    }

    @Test fun neverRetriesOnceAPacketArrived() {
        // datagrams but no complete, decodable frame: the scope is streaming, so no second START
        val w = VideoWatchdog(startedAt = 0)
        w.onPacket(100)
        assertFalse(w.shouldRetryStart(60_000))
        assertEquals(0, w.retries)
    }

    @Test fun retriesAreCapped() {
        val w = VideoWatchdog(startedAt = 0)
        var now = 0L
        repeat(10) { now += VideoWatchdog.RETRY_MS + 1; w.shouldRetryStart(now) }
        assertEquals(VideoWatchdog.MAX_RETRIES, w.retries)
    }

    @Test fun stallOnlyAfterVideoFlowed() {
        val w = VideoWatchdog(startedAt = 0)
        assertFalse(w.stalled(10_000))  // no frames yet: that's "waiting", not "stalled"
        w.onFrame(10_000)
        assertFalse(w.stalled(10_000 + VideoWatchdog.STALL_MS))
        assertTrue(w.stalled(10_001 + VideoWatchdog.STALL_MS))
        w.onPacket(20_000)
        assertFalse(w.stalled(20_000))
    }
}
