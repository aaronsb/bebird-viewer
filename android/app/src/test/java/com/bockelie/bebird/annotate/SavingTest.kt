// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.annotate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SavingTest {
    @Test fun bothFilesSavedGoesBackToTheLiveView() {
        assertNull(SaveProgress().start().finish(SaveOutcome("bebird-x.jpg", complete = true)))
    }

    @Test fun aFailedOriginalStaysPausedAndRetriesBoth() {
        val after = SaveProgress().start().finish(SaveOutcome(null, complete = false))
        assertEquals(SaveProgress(saving = false, savedOriginal = null), after)
    }

    @Test fun aFailedCopyStaysPausedAndRetriesOnlyTheCopy() {
        val after = SaveProgress().start().finish(SaveOutcome("bebird-x (1).jpg", complete = false))!!
        assertFalse(after.saving)
        assertEquals("bebird-x (1).jpg", after.savedOriginal)
        // a second failure keeps the original's name
        assertEquals("bebird-x (1).jpg", after.start().finish(SaveOutcome("bebird-x (1).jpg", complete = false))!!.savedOriginal)
        assertNull(after.start().finish(SaveOutcome("bebird-x (1).jpg", complete = true)))
    }

    @Test fun aDrawingFailureNamesTheFileThatWasntWritten() {
        // first try: nothing saved yet, so the still
        assertEquals("bebird-x.jpg", drawFailureName(null, "bebird-x.jpg"))
        // a retry: the original is saved, the copy is what failed
        assertEquals("bebird-x (1)_annotated.jpg", drawFailureName("bebird-x (1).jpg", "bebird-x.jpg"))
    }

    @Test fun marksAreFrozenWhileSaving() {
        assertTrue(AnnotateRules.canEdit(SaveProgress()))
        assertFalse(AnnotateRules.canEdit(SaveProgress().start()))
        assertTrue(AnnotateRules.canEdit(SaveProgress(savedOriginal = "x.jpg")))
    }

    @Test fun annotatingAndRecordingExcludeEachOther() {
        assertTrue(AnnotateRules.canAnnotate(recording = false, annotating = false, pausing = false))
        assertFalse(AnnotateRules.canAnnotate(recording = true, annotating = false, pausing = false))
        assertFalse(AnnotateRules.canAnnotate(recording = false, annotating = true, pausing = false))
        assertFalse(AnnotateRules.canAnnotate(recording = false, annotating = false, pausing = true))  // a double tap
        assertTrue(AnnotateRules.canRecord(annotating = false, pausing = false))
        assertFalse(AnnotateRules.canRecord(annotating = true, pausing = false))
        assertFalse(AnnotateRules.canRecord(annotating = false, pausing = true))
    }
}
