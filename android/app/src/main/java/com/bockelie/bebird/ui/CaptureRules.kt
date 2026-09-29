// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.ui

import com.bockelie.bebird.annotate.AnnotateRules

/**
 * What a capture control may start. Nothing new once Quit is under way (#54): it has already
 * finished the recording and is switching the scope off. Stopping a recording is always allowed.
 */
object CaptureRules {
    fun canSnapshot(quitting: Boolean) = !quitting

    fun canRecord(annotating: Boolean, pausing: Boolean, quitting: Boolean) =
        !quitting && AnnotateRules.canRecord(annotating, pausing)

    fun canAnnotate(recording: Boolean, annotating: Boolean, pausing: Boolean, quitting: Boolean) =
        !quitting && AnnotateRules.canAnnotate(recording, annotating, pausing)
}
