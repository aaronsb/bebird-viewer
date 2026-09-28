// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.proto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.roundToInt

class ProtocolTest {
    private fun hex(s: String) = s.split(" ").map { it.toInt(16).toByte() }.toByteArray()

    @Test fun ports() {
        assertEquals(58080, Protocol.DATA_PORT)
        assertEquals(58090, Protocol.COMMAND_PORT)
        assertEquals(58099, Protocol.BEACON_PORT)
        assertEquals(58081, Protocol.CLIENT_VIDEO_PORT)
    }

    @Test fun commandBytes() {
        assertArrayEquals(hex("20 36"), Protocol.START)
        assertArrayEquals(hex("20 37"), Protocol.STOP)
        assertArrayEquals(hex("66 3A"), Protocol.BATTERY)
        assertArrayEquals(hex("66 3C FF"), Protocol.LIGHT_COMMIT)
        assertArrayEquals(hex("66 3C FE"), Protocol.LIGHT_QUERY)
        assertArrayEquals(hex("66 39 01 01"), Protocol.BOARD_INFO)
        assertArrayEquals(hex("66 3C 1E"), Protocol.lightSet(30))
    }

    private fun isPowerOff(u: List<Int>) = u.size >= 2 && u[0] == 0x66 && u[1] == 0x3E
    private fun isCameraOff(u: List<Int>) = u.size >= 3 && u[0] == 0x66 && u[1] == 0x3F && u[2] == 0x01

    @Test fun powerOffIsTheOnlyProducerOf663E_and663F01IsNeverBuilt() {
        // 66 3E powers the scope off; 66 3F 01 .. switches the ES camera off until a power cycle.
        // Enumerate every no-argument ByteArray accessor the JVM sees (internal ones included,
        // under a mangled name), plus the parameterised builders.
        val accessors = Protocol::class.java.declaredMethods.filter {
            it.parameterCount == 0 && it.returnType == ByteArray::class.java
        }.onEach { it.isAccessible = true }  // private ones too
        assertTrue("found ${accessors.map { it.name }}", accessors.size >= 7)
        val producers = mutableListOf<String>()
        for (m in accessors) {
            val c = m.invoke(Protocol) as ByteArray
            val u = c.map { it.toInt() and 0xFF }
            assertFalse("forbidden ${hexOf(c)} from ${m.name}", isCameraOff(u))
            if (isPowerOff(u)) producers += m.name
        }
        assertEquals(1, producers.size)
        assertTrue(producers.single(), producers.single().startsWith("powerOff"))
        for (c in (0..100).flatMap { Protocol.lightCommands(it) }) {
            val u = c.map { it.toInt() and 0xFF }
            assertFalse("forbidden ${hexOf(c)}", isPowerOff(u) || isCameraOff(u))
        }
        assertArrayEquals(hex("66 3E"), Protocol.powerOff())
    }

    @Test fun powerOffIsCalledOnlyByScopeSessionPowerOff() {
        // The fence in the sources (declared as inputs of the test task, so a comment-only change
        // still reruns this): one call site, in ScopeSession, and no other 66 3E byte anywhere.
        val roots = listOf("src/main/java", "src/main/kotlin").map(::File).filter { it.isDirectory }
        assertTrue("run from the module directory: ${File("src/main").absolutePath}", roots.isNotEmpty())
        val sources = roots.flatMap { r -> r.walk().filter { it.isFile && it.extension in setOf("kt", "java") }.toList() }
        assertTrue(sources.size > 10)
        fun files(what: Regex) = sources.filter { what.containsMatchIn(it.readText()) }.map { it.name }.sorted()
        fun count(name: String, what: Regex) = what.findAll(sources.single { it.name == name }.readText()).count()

        // Protocol.powerOff(), Protocol::powerOff, or an import of it: only ScopeSession, once.
        val ref = Regex("""Protocol\s*(\.|::)\s*powerOff\b""")
        assertEquals(listOf("ScopeSession.kt"), files(ref))
        assertEquals(1, count("ScopeSession.kt", ref))
        // Any other mention of powerOff (with(Protocol) { powerOff() }, an alias) shows up here. The
        // UI and ScopeConnection call ScopeConnection/ScopeSession.powerOff, the fenced path.
        assertEquals(listOf("Protocol.kt", "ScopeConnection.kt", "ScopeSession.kt", "ViewerScreen.kt"), files(Regex("""\bpowerOff\b""")))

        // 0x3E in any case, anywhere: only Protocol's one. If a new one isn't 66 3E (a glyph, a
        // colour), allow-list its file here.
        val hex = Regex("""\b0[xX]3[eE]\b""")
        assertEquals(listOf("Protocol.kt"), files(hex))
        assertEquals(1, count("Protocol.kt", hex))
        // and no 66 3E spelled in decimal in Protocol
        assertEquals(0, count("Protocol.kt", Regex("""(?<![\d.])62(?![\d.])""")))
    }

    private fun hexOf(c: ByteArray) = c.joinToString(" ") { "%02X".format(it) }

    @Test fun commandsAreFreshCopies() {
        Protocol.START[0] = 0
        assertArrayEquals(hex("20 36"), Protocol.START)
    }

    @Test fun lightSetThenCommit() {
        val cmds = Protocol.lightCommands(50)
        assertEquals(2, cmds.size)
        assertArrayEquals(hex("66 3C 32"), cmds[0])
        assertArrayEquals(hex("66 3C FF"), cmds[1])
    }

    @Test(expected = IllegalArgumentException::class)
    fun lightSetRejectsCommitValue() {
        Protocol.lightSet(0xFF)
    }

    @Test fun lightPercentMapping() {
        assertEquals(0, Protocol.lightPercentToRaw(0))
        assertEquals(22, Protocol.lightPercentToRaw(1))
        assertEquals(36, Protocol.lightPercentToRaw(50))
        assertEquals(50, Protocol.lightPercentToRaw(100))
        assertEquals(0, Protocol.lightPercentToRaw(-5))
        assertEquals(50, Protocol.lightPercentToRaw(150))
    }

    @Test fun lightPercentMatchesViewerFormulaEverywhere() {
        // viewer.py: round(22 + (level - 1) * 28 / 99); no .5 ties occur, so plain rounding agrees
        for (p in 1..100) {
            val exact = 22 + (p - 1) * 28.0 / 99
            assertEquals("percent $p", exact.roundToInt(), Protocol.lightPercentToRaw(p))
        }
    }

    @Test fun batteryReply() {
        val b = Protocol.decodeBattery(hex("00 02 00 57"))!!
        assertEquals(2, b.state)
        assertEquals(87, b.percent)
        assertEquals("charging", b.stateName)
        assertEquals("battery", Protocol.decodeBattery(hex("00 00 00 64"))!!.stateName)
        assertEquals("7", Protocol.Battery(7, 0).stateName)
        assertNull(Protocol.decodeBattery(hex("00 02 00")))
    }

    @Test fun beaconBatteryPacking() {
        assertEquals(Protocol.Battery(1, 100), Protocol.unpackBattery(65636))
        assertEquals(Protocol.Battery(3, 100), Protocol.unpackBattery(3 shl 16 or 100))
    }

    @Test fun lightReply() {
        assertEquals(36, Protocol.decodeLightLevel(hex("24")))
        assertEquals(200, Protocol.decodeLightLevel(hex("C8")))  // unsigned
        assertNull(Protocol.decodeLightLevel(hex("00 02 00 57")))
    }

    @Test fun rollAngle() {
        assertEquals(0, Protocol.rollAngle(1, 0))
        assertEquals(255, Protocol.rollAngle(1, 0xFF))
        assertEquals(256, Protocol.rollAngle(2, 0))
        assertEquals(359, Protocol.rollAngle(2, 103))
        assertEquals(47, Protocol.rollAngle(1, 47))
        assertEquals(0xF0, Protocol.rollAngle(1, (0xF0).toByte().toInt()))  // sign-extended byte
    }
}
