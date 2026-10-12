package dev.kyungmin.kcoach.phone.ui

/** Read-only snapshot of the active workout that the Compose screen renders. */
data class WorkoutUiModel(
    val routineName: String,
    val isPreview: Boolean,
    val paused: Boolean,
    val startedAtMs: Long,
    val totalPausedMs: Long,
    val pausedSinceMs: Long,
    val exerciseIndex: Int,
    val exercises: List<ExerciseUi>,
    val usePounds: Boolean,
    val restStartAtMs: Long,
    val restEndAtMs: Long,
    val pausedRestRemainingMs: Long,
    val nextSetText: String?,
)

data class ExerciseUi(
    val id: String,
    val name: String,
    val muscles: String,
    val weighted: Boolean,
    val restSeconds: Int,
    val targetReps: Int,
    val performedReps: Int,
    val completedWorkSets: Int,
    val workSetCount: Int,
    val currentSetIndex: Int,
    val sets: List<SetUi>,
    /** Done and planned volume in the displayed unit; 0 target means bodyweight only. */
    val volumeDone: Int,
    val volumeTarget: Int,
)

data class SetUi(
    val uid: Int,
    val label: String,
    val weightText: String,
    val reps: Int,
    val completed: Boolean,
    val warmup: Boolean,
    val grip: String,
    val restSeconds: Int,
    val customRest: Boolean,
)

enum class SetField { WEIGHT, REPS }

interface WorkoutActions {
    fun onBack()
    fun onMenu()
    fun onExerciseInfo(exerciseIndex: Int)
    fun onPageSettled(exerciseIndex: Int)
    fun onEditValue(exerciseIndex: Int, setIndex: Int, field: SetField, value: String, applyAll: Boolean)
    fun onEditFinished()
    fun onToggleSet(exerciseIndex: Int, setIndex: Int)
    fun onSetMenu(exerciseIndex: Int, setIndex: Int)
    fun onAddSet(exerciseIndex: Int)
    fun onRemoveSet(exerciseIndex: Int, setIndex: Int)
    fun onUndoRemoveSet()
    fun onSetRest(exerciseIndex: Int, setIndex: Int)
    fun onAllSetsDone(exerciseIndex: Int)
    fun onAdjustRest(deltaSeconds: Int)
    fun onRestPreset(seconds: Int)
    fun onSkipRest()
    fun onTogglePause()
    fun onStop()
    fun onToggleUnit()
}
