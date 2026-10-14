package dev.kyungmin.kcoach.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PairedCapturePolicyTest {
    @Test fun referenceCapturePreservesPreparationAndDoesNotStopAtTheRequestedCount() {
        assertTrue(CapturePurpose.forSession("paired-reference").preservePreparation)
        assertFalse(CapturePurpose.forSession("paired-reference").stopAtTarget)
        assertFalse(CapturePurpose.forSession("workout").stopAtTarget,
            "A target is a goal, not proof that the user's set ended")
        assertEquals(CapturePurpose.PAIRED_REFERENCE, CapturePurpose.forSession("paired-reference"))
    }
}
