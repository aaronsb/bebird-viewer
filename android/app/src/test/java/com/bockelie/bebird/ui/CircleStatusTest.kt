// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.ui

import android.net.Network
import com.bockelie.bebird.band.PixelText
import com.bockelie.bebird.band.Fonts
import com.bockelie.bebird.wifi.ScopeWifi
import com.bockelie.bebird.wifi.ScopeWifi.Target
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CircleStatusTest {
    /** An instance of a framework class without running its (stubbed) constructor. */
    private inline fun <reified T> blank(): T {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        return unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, T::class.java) as T
    }

    private val states = listOf(
        ScopeWifi.State.Idle,
        ScopeWifi.State.Requesting,
        ScopeWifi.State.Available(blank<Network>(), Target.AnyScope),
        ScopeWifi.State.Lost,
        ScopeWifi.State.Unavailable(Target.AnyScope),
        ScopeWifi.State.Failed("permission revoked"),
    )

    private fun text(id: Int) = AppStrings.text(id)

    @Test fun eachStateHasItsMessage() {
        assertEquals(
            listOf(
                CircleStatus.NOT_CONNECTED, CircleStatus.CONNECTING, CircleStatus.WAITING,
                CircleStatus.LOST, CircleStatus.NOT_FOUND, CircleStatus.FAILED,
            ),
            states.map { CircleStatus.of(it, hasFrame = false) },
        )
    }

    @Test fun aPictureHidesTheMessageInEveryState() {
        for (s in states) assertNull("$s", CircleStatus.of(s, hasFrame = true))
    }

    @Test fun theWordingNamesTheButtonOnScreen() {
        // Connect shows while idle, not found or failed; Reconnect only works once joined;
        // while lost the top button is Disconnect.
        assertEquals(
            mapOf(
                CircleStatus.NOT_CONNECTED to ("NOT CONNECTED" to "Turn the scope on, then tap Connect"),
                CircleStatus.CONNECTING to ("CONNECTING…" to "Pick the scope if Android asks"),
                CircleStatus.WAITING to ("WAITING FOR PICTURE…" to "If none comes, tap Reconnect"),
                CircleStatus.LOST to ("CONNECTION LOST" to "Tap Disconnect, then Connect"),
                CircleStatus.NOT_FOUND to ("SCOPE NOT FOUND" to "Is it on and nearby? Tap Connect"),
                CircleStatus.FAILED to ("COULDN’T CONNECT" to "Tap Connect to try again"),
            ),
            CircleStatus.entries.associateWith { text(it.title) to text(it.hint) },
        )
    }

    @Test fun terminusHasEveryGlyphOfEveryMessage() {
        for (s in CircleStatus.entries) for (id in listOf(s.title, s.hint)) {
            for (cp in text(id).codePoints()) {
                assertNotNull("$s: no Terminus glyph for U+%04X".format(cp), Fonts.terminus.glyph(cp))
            }
        }
    }

    @Test fun everyMessageFitsTheCircle() {
        // 480 px is the smallest drawn size on a screen at least that wide; 240 is a small window
        val circle = PixelText(Fonts.source)
        for (s in CircleStatus.entries) for (side in listOf(480, 240)) {
            val paragraphs = listOf(text(s.title), text(s.hint))
            val lines = circle.layout(paragraphs, side)
            assertNotNull("$s at $side", lines)
            assertEquals("$s at $side", paragraphs.joinToString(" "), lines!!.joinToString(" ") { it.text })
        }
    }
}
