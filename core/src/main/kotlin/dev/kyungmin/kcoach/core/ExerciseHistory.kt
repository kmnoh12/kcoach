package dev.kyungmin.kcoach.core

data class LoggedSet(val weightKg: Double?, val reps: Int)

/** What was actually done for one exercise in one session. */
data class ExerciseLog(val exerciseId: String, val epochDay: Long, val sets: List<LoggedSet>, val sessionId: String? = null, val startedAtMs: Long = 0) {
    val volumeKg: Double get() = sets.sumOf { (it.weightKg ?: 0.0) * it.reps }
    val totalReps: Int get() = sets.sumOf { it.reps }
    val topWeightKg: Double? get() = sets.filter { it.reps > 0 }.mapNotNull { it.weightKg }.maxOrNull()
}

/** One line per log: `exerciseId<TAB>epochDay<TAB>weight:reps,weight:reps` (weight `-` = bodyweight). */
object ExerciseHistoryCodec {
    fun encode(logs: List<ExerciseLog>): String = logs.joinToString("\n") { log ->
        val sets = log.sets.joinToString(",") { "${it.weightKg?.toString() ?: "-"}:${it.reps}" }
        "${log.exerciseId}\t${log.epochDay}\t$sets" + (log.sessionId?.let { "\t$it\t${log.startedAtMs}" } ?: "")
    }

    fun decode(text: String): List<ExerciseLog> = text.lineSequence().mapNotNull { line ->
        val parts = line.split('\t')
        if (parts.size !in 3..5) return@mapNotNull null
        val day = parts[1].toLongOrNull() ?: return@mapNotNull null
        val sets = parts[2].split(',').mapNotNull { cell ->
            val (w, r) = cell.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
            LoggedSet(w.toDoubleOrNull(), r.toIntOrNull() ?: return@mapNotNull null)
        }
        if (sets.isEmpty()) null else ExerciseLog(parts[0], day, sets, parts.getOrNull(3)?.takeIf { it.isNotBlank() }, parts.getOrNull(4)?.toLongOrNull() ?: 0)
    }.toList()
}

/** Per-exercise memory of past sessions; immutable, newest first. */
class ExerciseHistory(logs: List<ExerciseLog> = emptyList()) {
    private val byExercise: Map<String, List<ExerciseLog>> =
        logs.groupBy { it.exerciseId }.mapValues { (_, list) -> list.sortedWith(compareByDescending<ExerciseLog> { it.epochDay }.thenByDescending { it.startedAtMs }) }

    val all: List<ExerciseLog> get() = byExercise.values.flatten()

    fun last(exerciseId: String): ExerciseLog? = byExercise[exerciseId]?.firstOrNull()

    fun recent(exerciseId: String, count: Int = 5): List<ExerciseLog> = byExercise[exerciseId].orEmpty().take(count)

    fun bestVolume(exerciseId: String): ExerciseLog? = byExercise[exerciseId]?.maxByOrNull { it.volumeKg }

    /** The single heaviest set ever logged, with the day it happened. */
    fun heaviestSet(exerciseId: String): Pair<Long, LoggedSet>? =
        byExercise[exerciseId].orEmpty()
            .flatMap { log -> log.sets.filter { it.weightKg != null && it.reps > 0 }.map { log.epochDay to it } }
            .maxByOrNull { it.second.weightKg!! }

    /** New logs replace only their own session; legacy logs retain day-based replacement. */
    fun with(log: ExerciseLog): ExerciseHistory {
        val kept = byExercise[log.exerciseId].orEmpty().filter { if (log.sessionId != null) it.sessionId != log.sessionId else it.sessionId != null || it.epochDay != log.epochDay }
        val updated = (listOf(log) + kept).sortedWith(compareByDescending<ExerciseLog> { it.epochDay }.thenByDescending { it.startedAtMs }).take(MAX_PER_EXERCISE)
        return ExerciseHistory(byExercise.filterKeys { it != log.exerciseId }.values.flatten() + updated)
    }

    companion object {
        const val MAX_PER_EXERCISE = 20
    }
}

sealed interface NextTarget {
    val reason: String

    data class RaiseWeight(val toKg: Double, override val reason: String) : NextTarget
    data class AddRep(override val reason: String) : NextTarget
    data class Hold(override val reason: String) : NextTarget
}

/**
 * Turns the last session into a suggestion, using the same rules as
 * [ProgressionEngine]. The rep range is read off the plan: the planned reps are
 * its top and two reps below are its floor.
 */
object NextTargetAdvisor {
    fun advise(last: ExerciseLog, plannedReps: Int): NextTarget {
        val top = plannedReps.coerceAtLeast(1)
        val plan = ExercisePlan(
            id = last.exerciseId,
            name = last.exerciseId,
            sets = last.sets.map { SetPlan(top, it.weightKg, 0) },
            repRange = (top - 2).coerceAtLeast(1)..top,
        )
        val completed = last.sets.mapIndexed { index, set ->
            CompletedSet(last.exerciseId, index, set.reps, set.weightKg, null, null, RepSource.MANUAL, 0L)
        }
        val topWeight = last.topWeightKg
        val increment = if ((topWeight ?: 0.0) >= 20.0) 2.5 else 1.0
        return when (val decision = ProgressionEngine().decide(plan, completed, increment)) {
            is ProgressionDecision.IncreaseWeight ->
                if (topWeight == null) NextTarget.AddRep("맨몸 운동이라 반복을 1회 늘려 보세요")
                else NextTarget.RaiseWeight(topWeight + decision.incrementKg, decision.reason)
            is ProgressionDecision.AddReps -> NextTarget.AddRep(decision.reason)
            is ProgressionDecision.Hold -> NextTarget.Hold(decision.reason)
            is ProgressionDecision.ReduceLoad -> NextTarget.Hold(decision.reason)
        }
    }
}

/** Short wrist-sensor remarks shown under a finished set. */
object SetInsight {
    fun describe(tempoConsistency: Int?, lateSetDegradation: Double?): String? {
        val parts = buildList {
            tempoConsistency?.let { add("템포 $it") }
            lateSetDegradation?.let {
                val percent = Math.round(it * 100).toInt()
                when {
                    percent >= 8 -> add("후반 ${percent}% 느려짐")
                    percent <= -8 -> add("후반 ${-percent}% 빨라짐")
                    else -> add("속도 일정")
                }
            }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }
}
