package dev.kyungmin.kcoach.core

sealed interface SessionPhase {
    data object NotStarted : SessionPhase
    data class Working(
        val exerciseIndex: Int,
        val setIndex: Int,
    ) : SessionPhase
    data class Resting(
        val exerciseIndex: Int,
        val completedSetIndex: Int,
        val elapsedSeconds: Int,
        val targetSeconds: Int,
    ) : SessionPhase
    data object Completed : SessionPhase
}

sealed interface CoachCue {
    data class ShowSet(
        val exercise: ExercisePlan,
        val setIndex: Int,
    ) : CoachCue
    data class RestStarted(val targetSeconds: Int) : CoachCue
    data object RestTargetReached : CoachCue
    data class RecommendExtraRest(val seconds: Int, val reason: String) : CoachCue
    data object NudgeToStart : CoachCue
    data class NextExercise(val exercise: ExercisePlan) : CoachCue
    data object WorkoutCompleted : CoachCue
}

data class CoachUpdate(
    val phase: SessionPhase,
    val cues: List<CoachCue> = emptyList(),
)

class SessionCoach(
    private val routine: RoutinePlan,
    private val restPolicy: RestPolicy = RestPolicy(),
) {
    private var phase: SessionPhase = SessionPhase.NotStarted
    private val completedSets = mutableListOf<CompletedSet>()
    private var restTargetCueSent = false
    private var nudgeSent = false

    fun start(): CoachUpdate {
        require(routine.exercises.isNotEmpty()) { "Routine must contain at least one exercise" }
        require(phase == SessionPhase.NotStarted) { "Session already started" }
        phase = SessionPhase.Working(0, 0)
        return CoachUpdate(phase, listOf(CoachCue.ShowSet(routine.exercises.first(), 0)))
    }

    fun completeSet(set: CompletedSet): CoachUpdate {
        val working = phase as? SessionPhase.Working
            ?: error("A set can only be completed while working")
        val exercise = routine.exercises[working.exerciseIndex]
        require(set.exerciseId == exercise.id) { "Completed set does not match active exercise" }
        require(set.setIndex == working.setIndex) { "Completed set index does not match active set" }

        completedSets += set
        val target = exercise.sets[working.setIndex].restSeconds
        phase = SessionPhase.Resting(
            exerciseIndex = working.exerciseIndex,
            completedSetIndex = working.setIndex,
            elapsedSeconds = 0,
            targetSeconds = target,
        )
        restTargetCueSent = false
        nudgeSent = false
        return CoachUpdate(phase, listOf(CoachCue.RestStarted(target)))
    }

    fun tickRest(seconds: Int, heartRate: HeartRateSnapshot?): CoachUpdate {
        require(seconds > 0) { "Tick must advance time" }
        val resting = phase as? SessionPhase.Resting
            ?: error("Rest timer can only tick while resting")
        val elapsed = resting.elapsedSeconds + seconds
        phase = resting.copy(elapsedSeconds = elapsed)

        val cues = mutableListOf<CoachCue>()
        if (elapsed >= resting.targetSeconds && !restTargetCueSent) {
            restTargetCueSent = true
            cues += CoachCue.RestTargetReached
            val lastSet = completedSets.last()
            val extra = restPolicy.extraRestRecommendation(lastSet, heartRate)
            if (extra != null) cues += extra
        }
        if (elapsed >= resting.targetSeconds + restPolicy.nudgeDelaySeconds && !nudgeSent) {
            val lastSet = completedSets.last()
            if (!restPolicy.needsMoreRest(lastSet, heartRate)) {
                nudgeSent = true
                cues += CoachCue.NudgeToStart
            }
        }
        return CoachUpdate(phase, cues)
    }

    fun startNextSet(): CoachUpdate {
        val resting = phase as? SessionPhase.Resting
            ?: error("Next set can only start after rest")
        val exercise = routine.exercises[resting.exerciseIndex]
        val nextSet = resting.completedSetIndex + 1
        val cues = mutableListOf<CoachCue>()

        if (nextSet < exercise.sets.size) {
            phase = SessionPhase.Working(resting.exerciseIndex, nextSet)
            cues += CoachCue.ShowSet(exercise, nextSet)
        } else {
            val nextExercise = resting.exerciseIndex + 1
            if (nextExercise < routine.exercises.size) {
                val next = routine.exercises[nextExercise]
                phase = SessionPhase.Working(nextExercise, 0)
                cues += CoachCue.NextExercise(next)
                cues += CoachCue.ShowSet(next, 0)
            } else {
                phase = SessionPhase.Completed
                cues += CoachCue.WorkoutCompleted
            }
        }
        return CoachUpdate(phase, cues)
    }

    fun snapshot(): CoachUpdate = CoachUpdate(phase)

    fun results(): List<CompletedSet> = completedSets.toList()
}

class RestPolicy(
    val nudgeDelaySeconds: Int = 45,
    private val highHeartRateThreshold: Int = 130,
    private val nearFailureRir: Double = 1.0,
) {
    fun needsMoreRest(set: CompletedSet, heartRate: HeartRateSnapshot?): Boolean {
        val heartRateHigh = heartRate?.currentBpm?.let { it >= highHeartRateThreshold } == true
        val nearFailure = set.rir?.let { it <= nearFailureRir } == true
        return heartRateHigh || nearFailure
    }

    fun extraRestRecommendation(
        set: CompletedSet,
        heartRate: HeartRateSnapshot?,
    ): CoachCue.RecommendExtraRest? {
        if (!needsMoreRest(set, heartRate)) return null
        val reason = when {
            heartRate?.currentBpm?.let { it >= highHeartRateThreshold } == true ->
                "심박수가 아직 충분히 회복되지 않았어요"
            set.rir?.let { it <= nearFailureRir } == true ->
                "직전 세트가 실패에 가까웠어요"
            else -> "직전 세트가 실패에 가까웠어요"
        }
        return CoachCue.RecommendExtraRest(30, reason)
    }
}
