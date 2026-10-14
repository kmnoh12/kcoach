package dev.kyungmin.kcoach.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PersonalCalibrationProfileTest {
    @Test
    fun `five controlled external rotation reps create bounded personal profile`() {
        val records = mutableListOf<SensorCaptureRecord>()
        var timestamp = 1_000_000_000L
        repeat(5) {
            repeat(26) {
                records += record(timestamp, 1.05f)
                timestamp += 20_000_000L
            }
            repeat(26) {
                records += record(timestamp, -1.05f)
                timestamp += 20_000_000L
            }
            records += record(timestamp, 1.05f)
            timestamp += 20_000_000L
            timestamp += 600_000_000L
        }
        val result = PersonalCalibrationBuilder.build(
            SensorCaptureDocument(1, 50.0, records),
            "external_rotation",
            "케이블 익스터널 로테이션",
            nowMs = 123L,
        )

        val profile = assertIs<CalibrationBuildResult.Success>(result, result.toString()).profile
        assertTrue(profile.validRepCount >= 3)
        assertEquals("external_rotation-LEFT-123", profile.profileId)
        assertTrue(profile.minimumRom in 0.05..8.0)
        val applied = PersonalCalibrationBuilder.personalizedTrackingProfile(
            profile,
            ExerciseTrackingProfiles.resolve("external_rotation"),
        )
        assertEquals(profile.dominantAxis, applied.preferredAxis)
        assertEquals(profile.profileVersion, applied.calibrationProfileVersion)
        assertTrue(applied.id.contains(profile.profileId))
        assertTrue(applied.minimumLobeDisplacement < profile.minimumRom / 2.0)
        assertEquals(profile, PersonalCalibrationProfileCodec.decode(PersonalCalibrationProfileCodec.encode(profile)))
    }

    @Test
    fun `constant fake motion and manual exercise cannot create profile`() {
        val constant = List(20) { index -> record(1_000_000_000L + index * 20_000_000L, 0.12f) }
        assertIs<CalibrationBuildResult.Failed>(
            PersonalCalibrationBuilder.build(SensorCaptureDocument(1, 50.0, constant), "external_rotation", "외회전"),
        )
        val result = PersonalCalibrationBuilder.build(
            SensorCaptureDocument(1, 50.0, constant),
            "leg_press",
            "레그 프레스",
        )
        assertEquals("exercise_unobservable_from_wrist", assertIs<CalibrationBuildResult.Failed>(result).reason)
    }

    @Test
    fun `slow calibration must not reject a faster valid curl`() {
        val generic = ExerciseTrackingProfiles.resolve("barbell_biceps_curl")
        val calibration = assertIs<CalibrationBuildResult.Success>(PersonalCalibrationBuilder.build(
            SensorCaptureDocument(1, 50.0, CurlCycleRegressionTest.curlSamples(5, 3.0, 3.0, topHoldSeconds = 2.0).map(::SensorCaptureRecord)),
            "barbell_biceps_curl", "바벨 컬", nowMs = 123L,
        ))
        val personal = PersonalCalibrationBuilder.personalizedTrackingProfile(calibration.profile, generic)
        val samples = CurlCycleRegressionTest.curlSamples(10, 0.6, 0.6)
        fun count(profile: ExerciseTrackingProfile): Int {
            val detector = ProfiledMotionRepDetector(profile)
            return samples.count { detector.add(it) != null }
        }
        assertEquals(10, count(generic))
        assertEquals(10, count(personal), "Personal minimum duration ${personal.minimumRepMs} must not discard valid curls")
    }

    private fun record(timestampNs: Long, gyroY: Float) = SensorCaptureRecord(
        SensorSample(timestampNs, 0f, 9.80665f, 0f, 0f, gyroY, 0f, 3),
        110,
    )
}
