// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.control

import com.bockelie.bebird.control.LightControl.Command
import com.bockelie.bebird.control.LightControl.Status
import com.bockelie.bebird.proto.Protocol
import org.junit.Assert.assertEquals
import org.junit.Test

class LightControlTest {
    private val light = LightControl(level = 100, beforeOff = 100)

    /** Poll every 10 ms from [from] to [to] inclusive, collecting commands with their times. */
    private fun run(from: Long, to: Long, online: Boolean = true): List<Pair<Long, Command>> =
        (from..to step 10).flatMap { t -> light.poll(t, online).map { t to it } }

    @Test fun sliderIsSentOnceStillForTheQuietTime() {
        // dragging: every move restarts the 300 ms wait
        light.set(80, 0)
        assertEquals(emptyList<Any>(), run(0, 250))
        light.set(60, 250)
        assertEquals(Status.Pending, light.status)
        assertEquals(emptyList<Any>(), run(260, 540))
        light.set(40, 540)
        val sent = run(550, 900)
        val raw = Protocol.lightPercentToRaw(40)
        assertEquals(listOf(840L to Command.Set(raw)), sent)  // one send, 300 ms after the last move
        assertEquals(Status.Verifying(raw), light.status)
    }

    @Test fun setThenQueryThenConfirmed() {
        light.set(50, 0)
        val raw = Protocol.lightPercentToRaw(50)
        assertEquals(listOf(300L to Command.Set(raw), 800L to Command.Query), run(0, 1790))
        light.onReported(raw)
        assertEquals(Status.Verifying(raw), light.status)  // judged at the check time, not on reply
        run(1800, 1800)
        assertEquals(Status.Confirmed(raw), light.status)
    }

    @Test fun aDifferentOrMissingReplyIsAMismatch() {
        light.set(50, 0)
        val raw = Protocol.lightPercentToRaw(50)
        run(0, 1000)
        light.onReported(7)
        run(1010, 1800)
        assertEquals(Status.Mismatch(raw, 7), light.status)

        light.set(30, 2000)
        run(2000, 4000)
        assertEquals(Status.Mismatch(Protocol.lightPercentToRaw(30), null), light.status)
    }

    @Test fun aReplyToAnEarlierSendDoesNotConfirmANewOne() {
        light.set(50, 0)
        run(0, 900)                                       // sent, queried
        light.onReported(Protocol.lightPercentToRaw(50))  // ...answered
        light.set(20, 900)                                // the user moves on before the check
        run(910, 3000)
        assertEquals(Status.Mismatch(Protocol.lightPercentToRaw(20), null), light.status)
    }

    @Test fun toggleIsImmediateAndRemembersTheLevel() {
        light.set(40, 0)
        run(0, 2000)
        light.toggle(3000)
        assertEquals(0, light.level)
        assertEquals(listOf(3000L to Command.Set(0)), run(3000, 3000))
        light.toggle(4000)
        assertEquals(40, light.level)
        assertEquals(listOf(4000L to Command.Set(Protocol.lightPercentToRaw(40))), run(4000, 4000))
    }

    @Test fun sliderToZeroIsOffAndKeepsTheOnLevel() {
        light.set(35, 0)
        light.set(0, 10)
        assertEquals(0, light.level)
        assertEquals(35, light.beforeOff)
    }

    @Test fun offlineDropsSendsAndStreamingReapplies() {
        light.set(70, 0)
        assertEquals(emptyList<Any>(), run(0, 1000, online = false))
        assertEquals(Status.Idle, light.status)
        assertEquals(70, light.level)  // the level is kept for when video comes
        light.onStreaming(2000)
        assertEquals(listOf(3500L to Command.Set(Protocol.lightPercentToRaw(70))), run(2000, 3500))
    }

    @Test fun goingOfflineAbandonsTheCheck() {
        // sent and queried, then the connection drops before the check: no "!" for that
        light.set(50, 0)
        run(0, 900)
        assertEquals(emptyList<Any>(), run(910, 2000, online = false))
        assertEquals(Status.Idle, light.status)
        assertEquals(null, light.nextDue())
    }

    @Test fun noConfirmedMarkOutlivesTheConnection() {
        light.set(50, 0)
        run(0, 1500)
        light.onReported(Protocol.lightPercentToRaw(50))
        run(1510, 2000)
        assertEquals(Status.Confirmed(Protocol.lightPercentToRaw(50)), light.status)
        run(2010, 2010, online = false)
        assertEquals(Status.Idle, light.status)
    }

    @Test fun toggleDropsTheOldMark() {
        light.set(50, 0)
        run(0, 2000)
        light.onReported(7)
        light.toggle(2500)
        assertEquals(Status.Pending, light.status)  // not the previous level's mark
        run(2500, 2500, online = false)             // offline: dropped, and nothing to show
        assertEquals(Status.Idle, light.status)
    }

    @Test fun nextDueFollowsTheSchedule() {
        assertEquals(null, light.nextDue())
        light.set(50, 1000)
        assertEquals(1300L, light.nextDue())
        light.poll(1300, online = true)
        assertEquals(1800L, light.nextDue())  // the query
        light.poll(1800, online = true)
        assertEquals(2800L, light.nextDue())  // the check
        light.poll(2800, online = true)
        assertEquals(null, light.nextDue())
    }

    @Test fun levelsAreClamped() {
        light.set(250, 0)
        assertEquals(100, light.level)
        light.set(-3, 0)
        assertEquals(0, light.level)
    }
}
