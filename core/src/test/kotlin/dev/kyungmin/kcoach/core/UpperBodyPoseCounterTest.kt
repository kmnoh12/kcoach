package dev.kyungmin.kcoach.core

import kotlin.test.*

class UpperBodyPoseCounterTest {
    @Test fun `counts ten complete elbow cycles and not a top pause or small reversal`() {
        val c = UpperBodyPoseCounter(CameraMotion.ELBOW_FOLD)
        var t = 0L
        fun send(a: Double?, n: Int) { repeat(n) { c.observe(t, a); t += 100 } }
        send(165.0, 4)
        repeat(10) { send(60.0, 5); send(100.0, 3); send(165.0, 4) }
        assertEquals(10, c.reps)
        send(60.0, 10); send(95.0, 5); send(60.0, 5)
        assertEquals(10, c.reps)
        send(165.0, 4); assertEquals(11, c.reps)
    }
    @Test fun `occlusion cannot bridge a partial rep and normal counting recovers`() {
        val c = UpperBodyPoseCounter(CameraMotion.ELBOW_FOLD)
        var t = 0L
        fun send(a: Double?, n: Int) { repeat(n) { c.observe(t, a); t += 100 } }
        send(165.0, 4); send(60.0, 5); send(null, 10); send(165.0, 4)
        assertEquals(0, c.reps)
        send(60.0, 5); send(165.0, 4); assertEquals(1, c.reps)
    }
    @Test fun `press and lateral raise require return to their start position`() {
        for (mode in listOf(CameraMotion.ELBOW_PRESS, CameraMotion.SHOULDER_RAISE)) {
            val c = UpperBodyPoseCounter(mode)
            repeat(4) { c.observe(it * 100L, mode.restDegrees - 5) }
            repeat(4) { c.observe(400 + it * 100L, mode.excursionDegrees + 5) }
            assertEquals(0, c.reps)
            c.observe(900, mode.restDegrees - 5); assertEquals(1, c.reps)
        }
    }
    @Test fun `geometry rejects hidden joints and distinguishes left from right`() {
        val p = MutableList(33) { PosePoint(0.0, 0.0, 0.0, 0.0) }
        p[11] = PosePoint(0.0, 1.0, 0.0, 1.0)
        p[13] = PosePoint(0.0, 0.0, 0.0, 1.0)
        p[15] = PosePoint(1.0, 0.0, 0.0, 1.0)
        assertEquals(90.0, UpperBodyPoseGeometry.metric(p, CameraMotion.ELBOW_FOLD, true)!!, 0.001)
        assertNull(UpperBodyPoseGeometry.metric(p, CameraMotion.ELBOW_FOLD, false))
        p[15] = p[15].copy(visibility = 0.1)
        assertNull(UpperBodyPoseGeometry.metric(p, CameraMotion.ELBOW_FOLD, true))
        assertNull(CameraMotion.forExercise("leg_press"))
        assertNull(CameraMotion.forExercise("external_rotation"))
    }
}
