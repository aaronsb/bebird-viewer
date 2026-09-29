// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.focus

import org.junit.Assert.assertTrue
import org.junit.Test

/** Per-frame cost on the JVM, printed for the record; the bound only catches a gross regression. */
class FocusCostTest {
    @Test fun estimatorFitsTheFrameBudget() {
        val frames = SyntheticScope.approach(tip = true).toList()
        val est = FocusEstimator()
        for (f in frames) est.update(f.frame, f.t, f.roll)  // warm up the JIT
        val times = DoubleArray(frames.size)
        val timed = FocusEstimator()
        for ((i, f) in frames.withIndex()) {
            val t0 = System.nanoTime()
            timed.update(f.frame, f.t, f.roll)
            times[i] = (System.nanoTime() - t0) / 1e6
        }
        times.sort()
        val mean = times.average()
        val p95 = times[(times.size * 0.95).toInt()]
        println("focus estimator: %.2f ms/frame mean, p95 %.2f, max %.2f (%d frames)".format(mean, p95, times.last(), times.size))
        assertTrue("mean $mean ms/frame", mean < 20.0)
    }
}
