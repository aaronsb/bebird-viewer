// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.wifi

import android.net.Network
import com.bockelie.bebird.wifi.ScopeWifi.State
import com.bockelie.bebird.wifi.ScopeWifi.Target
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** ScopeWifi's bookkeeping: which request is current, and what a loss does to it (#37). */
class RequestSlotTest {
    private class Cb(val name: String) {
        override fun toString() = name
    }

    /** An instance of a framework class without running its (stubbed) constructor. */
    private inline fun <reified T> blank(): T {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        return unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, T::class.java) as T
    }

    private val slot = RequestSlot<Cb>()
    private val target = Target.Exact("bebird-ES-1", "12:34:56:78:9A:9F")

    private fun joined(cb: Cb = Cb("first")): Cb {
        assertTrue(slot.file(cb) {})
        slot.update(cb, State.Available(blank<Network>(), target))
        slot.identify(cb, ScopeWifi.Identity("bebird-ES-1", "12:34:56:78:9A:9F"))
        return cb
    }

    @Test fun aLossReleasesTheRequestExactlyOnce() {
        val cb = joined()
        assertTrue(slot.lost(cb))
        assertEquals(State.Lost, slot.state.value)
        assertNull(slot.identity.value)
        assertFalse(slot.isCurrent(cb))
        // Android repeating itself, or any later callback of that request, changes nothing
        assertFalse(slot.lost(cb))
        slot.update(cb, State.Available(blank<Network>(), target))
        assertEquals(State.Lost, slot.state.value)
        // nothing left for Disconnect to unregister
        assertNull(slot.release())
    }

    @Test fun lostStaysUntilTheNextRequestOrRelease() {
        val cb = joined()
        slot.lost(cb)
        assertFalse(slot.state.value.filed)
        val next = Cb("next")
        assertTrue(slot.file(next) {})  // Connect: nothing blocks the new request
        assertEquals(State.Requesting, slot.state.value)
    }

    @Test fun aLossAfterADeliberateReleaseIsIgnored() {
        val cb = joined()
        assertSame(cb, slot.release())
        assertFalse(slot.lost(cb))
        assertEquals(State.Idle, slot.state.value)
    }

    @Test fun releaseAfterALossClearsTheMarker() {
        // Disconnect or quitting after a loss: NOT CONNECTED from then on
        slot.lost(joined())
        assertNull(slot.release())
        assertEquals(State.Idle, slot.state.value)
    }

    @Test fun aLossOfAReplacedRequestLeavesTheNewOneAlone() {
        val old = joined()
        slot.release()
        val new = Cb("new")
        slot.file(new) {}
        assertFalse(slot.lost(old))
        assertEquals(State.Requesting, slot.state.value)
        assertTrue(slot.isCurrent(new))
    }

    @Test fun aLossWhileEndingOnPurposeReadsAsIdle() {
        // Disconnect or power off marks the request ending; the network may go before the release
        val cb = joined()
        slot.markEnding()
        assertTrue(slot.lost(cb))  // still released here, once
        assertEquals(State.Idle, slot.state.value)
        assertNull(slot.release())
        assertEquals(State.Idle, slot.state.value)
    }

    @Test fun theEndingMarkDoesNotOutliveItsRequest() {
        slot.markEnding()  // nothing filed: nothing to mark
        slot.lost(joined())
        assertEquals(State.Lost, slot.state.value)

        slot.release()
        joined(Cb("second"))
        slot.markEnding()
        slot.release()
        val third = joined(Cb("third"))  // a new request starts unmarked
        assertTrue(slot.lost(third))
        assertEquals(State.Lost, slot.state.value)
    }

    @Test fun oneRequestAtATime() {
        joined()
        var filed = false
        assertFalse(slot.file(Cb("second")) { filed = true })
        assertFalse(filed)
        slot.failed("not while one is filed")
        assertTrue(slot.state.value is State.Available)
    }

    @Test fun aRequestThatThrowsIsNotCurrent() {
        val cb = Cb("refused")
        try {
            slot.file(cb) { throw SecurityException("no permission") }
        } catch (_: SecurityException) {
            slot.failed("no permission")
        }
        assertFalse(slot.isCurrent(cb))
        assertEquals(State.Failed("no permission"), slot.state.value)
    }

    @Test fun notFoundReleasesWithoutTheLostMarker() {
        val cb = Cb("first")
        slot.file(cb) {}
        slot.unavailable(cb, target)
        assertEquals(State.Unavailable(target), slot.state.value)
        assertFalse(slot.lost(cb))
    }
}
