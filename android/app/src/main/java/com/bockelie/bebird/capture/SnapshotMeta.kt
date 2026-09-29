// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.capture

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * What a capture records about how it was taken, as viewer.py's snapshot metadata. Deliberately
 * nothing that identifies the scope itself (no serial, uuid or MAC): pictures get shared.
 */
data class SnapshotMeta(
    val taken: ZonedDateTime,
    val roll: Int?,
    val rotationApplied: Int,
    val autoRotate: Boolean,
    val trim: Int,
    val lightPercent: Int,
    val lightRaw: Int,
    val batteryPercent: Int?,
    val batteryState: String?,
    val fps: Int?,
    val zoom: Double,
    val zoomed: Boolean,
    val label: String?,
    val device: String?,  // nickname, or the SSID without "bebird-"
    val model: String?,   // from the scope's beacon, if heard
    val annotated: Boolean = false,  // marks drawn over the picture (#16)
    val scale: ScaleMeta? = null,    // the proximity scale drawn in, if any (#43)
) {
    /** ImageDescription: a readable ASCII line, as the desktop writes (no degree signs). */
    fun description(): String = buildString {
        append("roll ${roll ?: "?"} deg, rotated $rotationApplied deg (${if (autoRotate) "auto" else "manual"}) + trim $trim deg")
        append(", light $lightPercent% (scope $lightRaw)")
        if (batteryPercent != null) append(", battery $batteryPercent% (${batteryState ?: "?"})")
        if (zoomed) append(", zoom %.2fx crop".format(java.util.Locale.ROOT, zoom))
        // ImageDescription is ASCII-only: the label goes here only when it is ASCII (it is
        // always in UserComment, escaped, and in the UTF-16 XP tags)
        label?.takeIf { l -> l.all { it.code in 0x20..0x7E } }?.let { append(", label $it") }
        if (annotated) append(", annotated")
    }

    /** UserComment: the metadata as JSON, all ASCII (non-ASCII escaped as \uXXXX). */
    fun json(): String {
        val fields = linkedMapOf<String, Any?>(
            // like Python's isoformat(): seconds, and "+00:00" rather than "Z" for UTC
            "taken" to taken.withNano(0).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx")),
            "roll_deg" to roll,
            "rotation_applied_deg" to rotationApplied,
            "auto_rotate" to autoRotate,
            "trim_deg" to trim,
            "light_pct" to lightPercent,
            "light_scope_level" to lightRaw,
            "battery_pct" to batteryPercent,
            "battery_state" to batteryState,
            "fps" to fps,
            "zoom" to zoom,
            "zoomed_crop" to zoomed,
            "label" to label,
            "device" to device,
            "scope" to linkedMapOf("brand" to "bebird", "model" to model).filterValues { it != null },
        )
        // only on annotated copies, so other captures stay as the desktop writes them
        if (annotated) fields["annotated"] = true
        // likewise only when a scale is drawn in, so captures without one are unchanged
        scale?.let {
            fields["proximity_scale"] = linkedMapOf(
                "style" to it.style, "locked" to it.locked, "px_per_mm" to it.pxPerMm, "tolerance_pct" to it.tolerancePct,
            )
        }
        return Json.write(fields)
    }
}

/** A minimal JSON writer: maps, strings, numbers, booleans, null; output is pure ASCII. */
object Json {
    fun write(v: Any?): String = StringBuilder().also { write(v, it) }.toString()

    private fun write(v: Any?, out: StringBuilder) {
        when (v) {
            null -> out.append("null")
            is Boolean, is Int, is Long -> out.append(v)
            // JSON has no NaN or infinity
            is Double -> out.append(if (!v.isFinite()) "null" else if (v == Math.floor(v)) v.toLong().toString() else v.toString())
            is String -> string(v, out)
            is Map<*, *> -> {
                out.append('{')
                v.entries.forEachIndexed { i, (k, value) ->
                    if (i > 0) out.append(", ")
                    string(k.toString(), out)
                    out.append(": ")
                    write(value, out)
                }
                out.append('}')
            }
            else -> string(v.toString(), out)
        }
    }

    private fun string(s: String, out: StringBuilder) {
        out.append('"')
        for (c in s) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c.code < 0x20 || c.code > 0x7E -> out.append("\\u%04x".format(c.code))
                else -> out.append(c)
            }
        }
        out.append('"')
    }
}

/**
 * The proximity scale as drawn into a still: [style] ("ring", "bowtie", "bar"), whether the
 * estimator had [locked] it, and its [pxPerMm] in that image's pixels, good to ±[tolerancePct] %.
 */
data class ScaleMeta(val style: String, val locked: Boolean, val pxPerMm: Double, val tolerancePct: Int = 10)
