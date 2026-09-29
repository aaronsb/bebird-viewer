// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Nothing new starts once Quit has begun (#54); the annotate rules still hold otherwise. */
class CaptureRulesTest {
    @Test fun nothingStartsWhileQuitting() {
        assertFalse(CaptureRules.canSnapshot(quitting = true))
        assertFalse(CaptureRules.canRecord(annotating = false, pausing = false, quitting = true))
        assertFalse(CaptureRules.canAnnotate(recording = false, annotating = false, pausing = false, quitting = true))
    }

    @Test fun otherwiseAsBefore() {
        assertTrue(CaptureRules.canSnapshot(quitting = false))
        assertTrue(CaptureRules.canRecord(annotating = false, pausing = false, quitting = false))
        assertFalse(CaptureRules.canRecord(annotating = true, pausing = false, quitting = false))
        assertFalse(CaptureRules.canRecord(annotating = false, pausing = true, quitting = false))
        assertTrue(CaptureRules.canAnnotate(recording = false, annotating = false, pausing = false, quitting = false))
        assertFalse(CaptureRules.canAnnotate(recording = true, annotating = false, pausing = false, quitting = false))
    }
}
