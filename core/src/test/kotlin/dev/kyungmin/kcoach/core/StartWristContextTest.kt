package dev.kyungmin.kcoach.core

import kotlin.test.*
import java.util.Base64

class StartWristContextTest {
    private val context = StartWristContext("external_rotation", "owner", "LEFT", "OUTSIDE", "ELBOW", "PALM_UP_HORIZONTAL")
    @Test fun declarationsRoundTripWithoutTurningIntoSensorConfirmation() {
        assertEquals(context, StartWristContextCodec.decode(StartWristContextCodec.encode(context)))
        assertTrue(context.completeForDirectionValidation)
        assertEquals("UNCONFIRMED", StartWristOrientationPolicy.uncheckedStartStatus(context.exerciseId))
        assertFalse(context.copy(deviceTop = "UNKNOWN").completeForDirectionValidation)
        assertFalse(context.copy(variant = "OTHER").completeForDirectionValidation)
    }
    @Test fun deferredExercisesAndMismatchedVariantsCannotAcquireCandidateConditions() {
        assertFailsWith<IllegalArgumentException> { context.copy(exerciseId = "lat_pull_down") }
        assertFailsWith<IllegalArgumentException> { context.copy(variant = "NEUTRAL_ARM_DOWN") }
        assertFailsWith<IllegalArgumentException> { context.copy(deviceTop = "SCREEN_ROTATION") }
    }
    @Test fun malformedOrExtraPayloadDoesNotProduceAValidContext() {
        val encoded = StartWristContextCodec.encode(context)
        val bytes = Base64.getDecoder().decode(encoded)
        assertFails { StartWristContextCodec.decode(Base64.getEncoder().encodeToString(bytes + byteArrayOf(1))) }
        assertFails { StartWristContextCodec.decode(Base64.getEncoder().encodeToString(bytes.dropLast(1).toByteArray())) }
    }
    @Test fun additiveCommandPreservesLegacyFieldsAndRejectsOtherOwnerOrExercise() {
        val base = WearSync.withVerifiedPatternSelection(WearSync.withPatternWearing(
            WearSync.richCommand("START", "session", "external_rotation", "회전", 0, 8, 5.0, 60,
                setId = "set", attemptId = "attempt"), PatternWearingContext("owner", "LEFT", "OUTSIDE")))
        val bound = WearSync.parse(WearSync.withStartWristContext(base, context))
        assertEquals(WearSync.parse(base), bound.take(19))
        assertEquals(context, StartWristContextCodec.decode(bound[19]))
        assertFailsWith<IllegalArgumentException> { WearSync.withStartWristContext(base, context.copy(owner = "other")) }
    }
}
