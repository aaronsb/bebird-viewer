// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * One press-and-hold (#38), apart from Compose so its rules can be tested: the fill runs from 0
 * to 1 over [durationMs] while held, the hold completes exactly once per press, and a press let
 * go of within [tapMs] counts as a tap (for the "Hold to …" hint). Times are the caller's
 * monotonic milliseconds. Main thread only.
 */
class Hold(val durationMs: Long, private val tapMs: Long = TAP_MS) {
    /** How a press ended. */
    enum class Release { TAP, CANCELLED, DONE }

    private var pressedAt: Long? = null
    private var done = false

    val isHeld: Boolean get() = pressedAt != null

    /** Pressed at [now]; false (and nothing changes) while already held. */
    fun press(now: Long): Boolean {
        if (pressedAt != null) return false
        pressedAt = now
        done = false
        return true
    }

    /** How full the button is at [now]: 0 unless held, 1 once complete. */
    fun progress(now: Long): Float {
        val t = pressedAt ?: return 0f
        return if (done) 1f else ((now - t).toFloat() / durationMs).coerceIn(0f, 1f)
    }

    /** True once per press, the first time it is asked at or after [durationMs] of holding. */
    fun complete(now: Long): Boolean {
        val t = pressedAt ?: return false
        if (done || now - t < durationMs) return false
        done = true
        return true
    }

    /** Let go at [now]; null if not held. */
    fun release(now: Long): Release? {
        val t = pressedAt ?: return null
        val how = when {
            done -> Release.DONE
            now - t < tapMs -> Release.TAP
            else -> Release.CANCELLED
        }
        cancel()
        return how
    }

    /** The gesture was taken away (slid off, another gesture): no action, no hint. */
    fun cancel() {
        pressedAt = null
        done = false
    }

    companion object {
        /** Shorter than this is a tap, which only shows how to use the button. */
        const val TAP_MS = 400L
        const val DISCONNECT_MS = 2_000L
        const val QUIT_MS = 5_000L
    }
}

/**
 * A button that acts only once held for [durationMs] (#38): while held it fills from left to
 * right in its content colour, and let go early the fill drains back. A light haptic tick on
 * press, a stronger one on completion, and [onHeld] runs then, without waiting for the release;
 * holding on doesn't repeat it. A tap runs [onTap], for a hint. A screen reader gets [onHeld] as
 * the click action, with no hold, labelled [description] (or the visible text if null).
 */
@Composable
fun HoldButton(
    durationMs: Long,
    onHeld: () -> Unit,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    val hold = remember(durationMs) { Hold(durationMs) }
    val fill = remember { Animatable(0f) }
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val held by rememberUpdatedState(onHeld)
    val tapped by rememberUpdatedState(onTap)
    val fillColor = colors.contentColor.copy(alpha = FILL_ALPHA)
    Surface(
        shape = ButtonDefaults.shape,
        color = colors.containerColor,
        contentColor = colors.contentColor,
        modifier = modifier
            .semantics(mergeDescendants = true) {
                role = Role.Button
                description?.let { contentDescription = it }
                onClick { held(); true }
            }
            .pointerInput(hold) {
                awaitEachGesture {
                    awaitFirstDown()
                    if (!hold.press(uptimeMs())) return@awaitEachGesture
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    val ticker = scope.launch {
                        while (isActive) {
                            withFrameMillis {}
                            val t = uptimeMs()
                            fill.snapTo(hold.progress(t))
                            if (hold.complete(t)) {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                held()
                                break
                            }
                        }
                    }
                    val up = waitForUpOrCancellation()
                    ticker.cancel()
                    val how = if (up != null) hold.release(uptimeMs()) else { hold.cancel(); null }
                    up?.consume()
                    if (how == Hold.Release.TAP) tapped()
                    scope.launch { fill.animateTo(0f, tween(DRAIN_MS)) }
                }
            },
    ) {
        ProvideTextStyle(MaterialTheme.typography.labelLarge) {
            Box(Modifier.drawBehind { drawRect(fillColor, size = Size(size.width * fill.value, size.height)) }) {
                Row(
                    Modifier
                        .defaultMinSize(ButtonDefaults.MinWidth, ButtonDefaults.MinHeight)
                        .padding(contentPadding),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                    content = content,
                )
            }
        }
    }
}

private fun uptimeMs() = System.nanoTime() / 1_000_000

private const val FILL_ALPHA = 0.3f
private const val DRAIN_MS = 250
