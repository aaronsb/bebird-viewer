// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.control

import com.bockelie.bebird.proto.Protocol

/**
 * The tip light, as viewer.py drives it: the slider (0 % off, 1-100 % mapped to raw 22-50) is
 * sent only once it has been still for [quietMs]; the level is then set and committed, queried
 * [queryAfterMs] later and checked against the reply at [checkAfterMs]. The on/off toggle is sent
 * at once. After video starts the level is re-applied (after [assertAfterMs]) so the scope's
 * state matches the UI.
 *
 * Pure and single-threaded: the caller feeds it the time and polls it for commands to send.
 */
class LightControl(
    level: Int = 100,
    beforeOff: Int = 100,
    private val quietMs: Long = 300,
    private val queryAfterMs: Long = 500,
    private val checkAfterMs: Long = 1500,
    private val assertAfterMs: Long = 1500,
) {
    sealed interface Command {
        /** `66 3C raw` then `66 3C FF`. */
        data class Set(val raw: Int) : Command
        /** `66 3C FE`; the reply is one byte. */
        data object Query : Command
    }

    sealed interface Status {
        data object Idle : Status
        /** Moved, waiting for the slider to be still. */
        data object Pending : Status
        data class Verifying(val raw: Int) : Status
        data class Confirmed(val raw: Int) : Status
        /** The scope reported [reported] (null: no reply) where [raw] was sent. */
        data class Mismatch(val raw: Int, val reported: Int?) : Status
    }

    /** UI percent: 0 is off. */
    var level = level.coerceIn(0, 100); private set
    /** The level the toggle turns back on to. */
    var beforeOff = beforeOff.coerceIn(1, 100); private set
    var status: Status = Status.Idle; private set

    private var sendAt: Long? = null
    private var queryAt: Long? = null
    private var checkAt: Long? = null
    private var sent: Int? = null
    private var reported: Int? = null

    /** The slider moved to [percent]: sent once it has been still for [quietMs]. */
    fun set(percent: Int, now: Long) {
        update(percent)
        sendAt = now + quietMs
        status = Status.Pending
    }

    /** Off, or back on to the last level; sent at once. */
    fun toggle(now: Long) {
        update(if (level > 0) 0 else beforeOff)
        sendAt = now
    }

    /** Video started: re-apply the level shortly, as the desktop viewer does. */
    fun onStreaming(now: Long) {
        sendAt = now + assertAfterMs
    }

    /** A `66 3C FE` reply. */
    fun onReported(raw: Int) {
        reported = raw
    }

    /**
     * What to send now. When not [online], due sends are dropped (the level is kept and is
     * re-applied by [onStreaming]) and a pending check reports no reply.
     */
    fun poll(now: Long, online: Boolean): List<Command> {
        val out = mutableListOf<Command>()
        if (sendAt.isDue(now)) {
            sendAt = null
            if (online) {
                val raw = Protocol.lightPercentToRaw(level)
                out += Command.Set(raw)
                sent = raw
                reported = null
                queryAt = now + queryAfterMs
                checkAt = now + checkAfterMs
                status = Status.Verifying(raw)
            } else if (status == Status.Pending) {
                status = Status.Idle
            }
        }
        if (queryAt.isDue(now)) {
            queryAt = null
            if (online) out += Command.Query
        }
        if (checkAt.isDue(now)) {
            checkAt = null
            val raw = sent!!
            status = if (reported == raw) Status.Confirmed(raw) else Status.Mismatch(raw, reported)
        }
        return out
    }

    private fun update(percent: Int) {
        level = percent.coerceIn(0, 100)
        if (level > 0) beforeOff = level
    }

    private fun Long?.isDue(now: Long) = this != null && now >= this
}
