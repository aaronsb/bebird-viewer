// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.focus

/**
 * Every tunable of the focus / proximity estimator (docs/focus-detection.md), in seconds,
 * degrees, luma levels and fractions. The defaults are the desktop prototype's, tuned on
 * recordings at LED raw level 42; windows are in real time, never frame counts, unless the name
 * says otherwise.
 */
data class FocusConfig(
    // roll (§6)
    val jitterWindow: Double = 1.0,
    val coarseOn: Double = 5.0,
    val coarseOff: Double = 3.5,
    val careful: Double = 2.5,
    val restWindow: Double = 3.0,
    val restSd: Double = 0.3,
    val restArmed: Double = 10.0,
    // brightness trend and arming (§5)
    val slopeWindow: Double = 1.5,
    val slopeThreshold: Double = 0.12,
    val brightAlpha: Double = 0.3,
    val ambientWindow: Double = 30.0,
    val armWindow: Double = 12.0,
    val armRatio: Double = 1.4,
    val armMin: Double = 100.0,
    val armMinTip: Double = 40.0,
    val disarmRatio: Double = 1.3,
    val disarmFloor: Double = 0.8,
    val disarmHold: Double = 1.5,
    // sharpness and lock (§4)
    val sharpAlpha: Double = 0.35,
    val peakTau: Double = 30.0,
    val lockOn: Double = 0.90,
    val lockOff: Double = 0.82,
    val warmup: Double = 2.5,
    // state machine and CLOSE (§7)
    val zoneHold: Double = 0.4,
    val closeMotion: Double = 2.0,
    val closeHold: Double = 0.5,
    // tip mask (§3)
    val tipAlpha: Double = 0.04,
    val tipSdOn: Double = 6.0,
    val tipSdOff: Double = 14.0,
    val tipMinLuma: Int = 90,
    val tipMotion: Int = 3,
    val tipOffUpdates: Int = 50,
    val tipLearnMaxSaturated: Double = 0.40,
    val tipSaturatedLuma: Int = 250,
    val tipFastFrames: Int = 10,
    val tipFastAge: Double = 4.0,
    val tipFastMotion: Int = 8,
    val tipFastRange: Int = 12,
    val tipMinUpdates: Int = 10,
    val tipMaxGrow: Double = 0.08,
    val tipPriorX: Double = 0.70,
    val tipPriorY: Double = 0.55,
    val tipPriorMin: Int = 20,
    val tipRegion: Double = 0.40,
    val tipPresent: Double = 0.05,
    val tipDvClamp: Double = 40.0,
    val tipRebuildEvery: Int = 5,
    val blobRebuildEvery: Int = 10,
    val blobLuma: Int = 150,
    val blobEdge: Int = 3,
    val blobStep: Int = 6,
    val blobAlpha: Double = 0.1,
    val blobDecay: Double = 0.01,
    val blobOn: Double = 0.6,
    val blobOff: Double = 0.3,
    val blobReach: Int = 8,
)
