// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.focus

/**
 * A per-frame log exported from the reference prototype (src/test/resources/focus/): the
 * features it measured and the state it reached. Numbers only; the frames stay private.
 * k_sharp / k_bright are the rescale ratios applied on frames where the tip mask was rebuilt.
 */
class FeatureLog(val name: String, val rows: List<Row>) {
    class Row(
        val t: Double, val roll: Int, val bright: Double, val sharpRaw: Double, val motion: Double,
        val tip: Double, val kSharp: Double?, val kBright: Double?,
        val state: String, val close: Boolean, val armed: Boolean,
    )

    companion object {
        fun load(name: String): FeatureLog {
            val stream = FeatureLog::class.java.getResourceAsStream("/focus/$name.csv")
                ?: error("missing test resource focus/$name.csv")
            val lines = stream.bufferedReader().readLines()
            val head = lines.first().split(',')
            fun col(c: String) = head.indexOf(c).also { require(it >= 0) { "$name.csv has no $c" } }
            val cT = col("t"); val cR = col("roll"); val cB = col("bright"); val cS = col("sharp_raw")
            val cM = col("motion"); val cTip = col("tip"); val cKs = col("k_sharp"); val cKb = col("k_bright")
            val cSt = col("state"); val cC = col("close"); val cA = col("armed")
            val rows = lines.drop(1).filter { it.isNotBlank() }.map { line ->
                val f = line.split(',')
                Row(
                    f[cT].toDouble(), f[cR].toInt(), f[cB].toDouble(), f[cS].toDouble(), f[cM].toDouble(),
                    f[cTip].toDouble(), f[cKs].toDoubleOrNull(), f[cKb].toDoubleOrNull(),
                    f[cSt], f[cC] == "1", f[cA] == "1",
                )
            }
            return FeatureLog(name, rows)
        }

        /** Percent (rounded, as in the table of docs/focus-detection.md) of frames matching, per [step] s window. */
        fun <T> perWindow(items: List<T>, time: (T) -> Double, step: Double = 5.0, hit: (T) -> Boolean): List<Int> {
            val out = ArrayList<Int>()
            val tmax = time(items.last())
            var s = 0.0
            while (s <= tmax) {
                val w = items.filter { time(it) >= s && time(it) < s + step }
                if (w.isNotEmpty()) out += Math.round(100.0 * w.count(hit) / w.size).toInt()
                s += step
            }
            return out
        }
    }
}
