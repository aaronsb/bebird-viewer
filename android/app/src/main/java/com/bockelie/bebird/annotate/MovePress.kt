// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.annotate

/** What a Move press on a mark turns into. */
enum class PressOutcome { MOVE, DELETE, NONE }

/**
 * Deciding a Move press, apart from Compose. Until one of these happens the press is
 * undecided: the finger moves past the touch [slop] (a move), the long-press timeout runs out
 * with it still down and within the slop (a delete), or it lifts or is taken by something else
 * first (nothing).
 *
 * [present] is false once the first finger is no longer in the events; [elapsedMs] is the time
 * since the press. Pure.
 */
fun classifyMovePress(
    present: Boolean, pressed: Boolean, consumed: Boolean, distance: Float, slop: Float, elapsedMs: Long, timeoutMs: Long,
): PressOutcome? = when {
    !present || consumed || !pressed -> PressOutcome.NONE
    distance > slop -> PressOutcome.MOVE
    elapsedMs >= timeoutMs -> PressOutcome.DELETE
    else -> null
}
