// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.annotate

import com.bockelie.bebird.capture.CaptureNames

/** How one annotated save went: the original still's saved name (null if it wasn't saved), and whether the copy was saved too. */
data class SaveOutcome(val original: String?, val complete: Boolean)

/**
 * Where saving a paused frame stands: whether a save is running, and the original still's
 * name once it is saved, so a retry after a failed copy writes only the copy (named after it).
 */
data class SaveProgress(val saving: Boolean = false, val savedOriginal: String? = null) {
    fun start() = copy(saving = true)

    /** After [outcome]: null once both files are saved (back to the live view), else paused again, marks kept, ready to retry. */
    fun finish(outcome: SaveOutcome): SaveProgress? =
        if (outcome.complete) null else SaveProgress(saving = false, savedOriginal = outcome.original ?: savedOriginal)
}

/**
 * The file a failed drawing step is reported against: the annotated copy on a retry (the
 * original, [savedOriginal], is already saved), else the [still] about to be written.
 */
fun drawFailureName(savedOriginal: String?, still: String) = savedOriginal?.let(CaptureNames::annotated) ?: still

/** When annotate and recording may start; each excludes the other, including while a pause is being prepared. */
object AnnotateRules {
    fun canAnnotate(recording: Boolean, annotating: Boolean, pausing: Boolean) = !recording && !annotating && !pausing

    fun canRecord(annotating: Boolean, pausing: Boolean) = !annotating && !pausing

    /** Marks can't change while a save runs: the files get the marks as they were when Save was tapped. */
    fun canEdit(progress: SaveProgress) = !progress.saving
}
