package dev.kyungmin.kcoach.core

import kotlin.math.sqrt
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PatternDtwEquivalenceTest {
    @Test fun removingCellAllocationsPreservesDistanceAndTurnaroundIncludingTiedPaths() {
        val random = Random(20261014)
        repeat(80) { example ->
            val template = List(33) { List(6) { if (example < 4) example.toDouble() else random.nextDouble(-2.0, 2.0) } }
            val candidate = List(33) { List(6) { if (example < 4) 0.0 else random.nextDouble(-2.0, 2.0) } }
            val axes = if (example % 2 == 0) listOf(0) else listOf(0, 1, 2, 3, 4, 5)
            assertEquals(original(template, candidate, axes), BoundedPatternDtw.compare(template, candidate, axes))
        }
    }

    // Frozen pre-optimization matcher is the equivalence oracle, including predecessor tie order.
    private fun original(template: List<List<Double>>, candidate: List<List<Double>>, axes: List<Int>): BoundedPatternDtw.Match {
        val n = template.size
        val costs = Array(n) { DoubleArray(n) { Double.POSITIVE_INFINITY } }
        val previous = Array(n) { IntArray(n) { -1 } }
        val lengths = Array(n) { IntArray(n) }
        for (i in 0 until n) for (j in maxOf(0, i - 8)..minOf(n - 1, i + 8)) {
            val local = axes.sumOf { axis -> val d = template[i][axis] - candidate[j][axis]; d * d } / axes.size
            if (i == 0 && j == 0) { costs[i][j] = local; lengths[i][j] = 1; continue }
            val options = listOfNotNull(if (i > 0 && j > 0) (i - 1) to (j - 1) else null,
                if (i > 0) (i - 1) to j else null, if (j > 0) i to (j - 1) else null)
            val (a, b) = options.minByOrNull { (a, b) -> costs[a][b] } ?: continue
            costs[i][j] = local + costs[a][b]; lengths[i][j] = lengths[a][b] + 1; previous[i][j] = a * n + b
        }
        var i = n - 1; var j = n - 1
        val turn = mutableListOf<Int>()
        while (i >= 0 && j >= 0) {
            if (i == 16) turn += j
            val p = previous[i][j]; if (p < 0) break
            i = p / n; j = p % n
        }
        return BoundedPatternDtw.Match(sqrt(costs[n - 1][n - 1] / lengths[n - 1][n - 1]),
            turn.sorted().let { if (it.isEmpty()) 16 else it[it.size / 2] })
    }
}
