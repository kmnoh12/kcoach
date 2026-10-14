package dev.kyungmin.kcoach.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SavitzkyGolayFilterTest {
    @Test fun fourthOrderTimeFitKeepsTheShapeOfAnUnevenlySampledPolynomial() {
        val times = listOf(0L, 19L, 41L, 60L, 82L, 100L, 121L, 142L, 161L)
        val points = times.map { ms ->
            val t = ms / 100.0
            SignalPoint(ms * 1_000_000, 2 + 3 * t - 2 * t * t + t * t * t)
        }
        val filtered = SavitzkyGolayFilter.smooth(points, windowNs = 200_000_000, order = 4)
        assertEquals(points.map { it.timestampNs }, filtered.map { it.timestampNs })
        points.zip(filtered).forEach { (expected, actual) -> assertEquals(expected.value, actual.value, 1e-7) }
    }
}
