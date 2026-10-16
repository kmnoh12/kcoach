package dev.kyungmin.kcoach.phone

import android.content.Context
import dev.kyungmin.kcoach.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PerformedRepsTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private fun snapshot() = WorkoutSnapshot(
        sessionId = "background-session", routineId = "shoulders", exerciseIndex = 0, setIndex = 0,
        plannedSets = listOf(PlannedSetSnapshot(0, "external_rotation", 0, 10, 5.0, false), PlannedSetSnapshot(0, "external_rotation", 1, 10, 5.0, false)),
        performedReps = emptyMap(), phase = WorkoutPhase.WORKING, restEndAtMs = 0, pausedRestRemainingMs = 0,
        paused = false, startedAtMs = 1, pausedSinceMs = 0, totalPausedMs = 0, lastSequence = 0,
        lastCompletedSetKey = null, captureStates = emptyMap(),
    )
    private fun restoredCompleted(reps: Int): Pair<MainActivity, Any> {
        val store = SessionStore(app); store.saveActive(snapshot())
        store.completeSet(SetCompletionEvent("background-session", "external_rotation", 0, reps, 1_000, 1)).getOrThrow()
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        MainActivity::class.java.getDeclaredField("sessionStore").apply { isAccessible = true }.set(activity, store)
        val active = MainActivity::class.java.getDeclaredMethod("restoreActiveWorkout").apply { isAccessible = true }.invoke(activity)!!
        return activity to active
    }
    private fun record(activity: MainActivity, active: Any) {
        for (method in listOf("recordHistory", "recordWorkoutLog")) {
            MainActivity::class.java.getDeclaredMethod(method, active.javaClass).apply { isAccessible = true }.invoke(activity, active)
        }
    }
    private fun assertProjectedSixReps(log: ExerciseLog) {
        assertEquals(2, log.sets.size)
        assertEquals(listOf(true, false), log.sets.map { it.completed })
        assertEquals(listOf(true, false), log.sets.map { it.repsEntered })
        assertEquals(listOf(10, 10), log.sets.map { it.targetReps })
        assertEquals(listOf(6, 0), log.sets.map { it.reps })
        assertEquals(30.0, log.volumeKg, 0.001)
    }
    @Test fun measuredSixRepsProduceThirtyKgRatherThanTargetFifty() {
        val (activity, active) = restoredCompleted(6); record(activity, active)
        val exercise = ExerciseHistoryStore(app).load().last("external_rotation")!!
        assertProjectedSixReps(exercise)
        assertEquals(30.0, exercise.volumeKg, 0.001)
        assertEquals(30, WorkoutLogStore(app).load().single().volumeKg)
    }
    @Test fun finishingTheSameSessionTwiceDoesNotDuplicateStatistics() {
        val (activity, active) = restoredCompleted(6); record(activity, active); record(activity, active)
        assertEquals(1, WorkoutLogStore(app).load().size)
    }
    @Test fun watchCompletionNeverChangesThePlannedTarget() {
        val result = WorkoutSessionReducer.completeSet(snapshot(), SetCompletionEvent("background-session", "external_rotation", 0, 12, 1_000, 1)) as SetCompletionResult.Applied
        assertEquals(10, result.snapshot.plannedSets.first().targetReps)
    }
    private fun watchSnapshot(session: String = "background-session"): WorkoutSnapshot {
        val store = SessionStore(app); store.loadActive(); store.saveActive(snapshot().copy(sessionId = session, startedAtMs = if (session == "two") 2 else 1))
        return (store.completeWatchSet(SetCompletionEvent(session, "external_rotation", 0, 10, 0, 0), 1_000).getOrThrow() as SetCompletionResult.Applied).snapshot
    }
    private fun nativeCount(session: String, count: Int?) {
        val fields = MutableList(24) { "" }
        fields[0] = "10"; fields[1] = count?.toString().orEmpty(); fields[2] = count?.toString().orEmpty()
        fields[11] = "0.9"; fields[12] = "test"; fields[13] = "WRIST_IMU_CAUTION"
        fields[14] = "generic-wrist"; fields[16] = "C_PERSONALIZED_AUTO_COUNT_BETA"
        val manifest = WireEnvelope(messageType = WireMessageType.CAPTURE_MANIFEST, sessionId = session,
            source = WireSource.WATCH, sentAtElapsedMs = 0, sentAtWallMs = 0, payload = mapOf(
                WirePayloadKeys.CAPTURE_ID to "count-capture", WirePayloadKeys.EXERCISE_ID to "external_rotation",
                WirePayloadKeys.EXERCISE_NAME to "외회전", WirePayloadKeys.SET_INDEX to "0"))
        app.getSharedPreferences("wear-sync", Context.MODE_PRIVATE).edit()
            .putString("analysis:count-capture", fields.joinToString("|"))
            .putString(CaptureInbox.manifestKey("count-capture"), WireCodec.encode(manifest)).commit()
    }
    @Test fun lateConfirmedAnalysisUpdatesClosedHistoryAndStatisticsWithoutDuplicatingThem() {
        val original = watchSnapshot(); val store = FinishedWorkoutStore(app)
        store.finish(original, "어깨전문", 12); SessionStore(app).apply { loadActive(); clearActive() }
        nativeCount(original.sessionId, 6); store.reconcile(original.sessionId)
        assertProjectedSixReps(ExerciseHistoryStore(app).load().last("external_rotation")!!)
        assertEquals(30, WorkoutLogStore(app).load().single().volumeKg)
        store.finish(original, "어깨전문", 99); store.replay()
        assertEquals(1, WorkoutLogStore(app).load().size)
        assertEquals(12, WorkoutLogStore(app).load().single().minutes)
        assertEquals(30, WorkoutLogStore(app).load().single().volumeKg)
        assertTrue(NextTargetAdvisor.advise(ExerciseHistoryStore(app).load().last("external_rotation")!!, 10) is NextTarget.Hold)
    }
    @Test fun unknownAnalysisPreservesWatchCountAndConfirmedZeroStaysZero() {
        val original = watchSnapshot(); val store = FinishedWorkoutStore(app); store.finish(original, "어깨전문", 12)
        nativeCount(original.sessionId, null); store.reconcile(original.sessionId)
        assertEquals(50, WorkoutLogStore(app).load().single().volumeKg)
        nativeCount(original.sessionId, 0); store.reconcile(original.sessionId)
        assertEquals(0, WorkoutLogStore(app).load().single().volumeKg)
        assertEquals(0, SessionStore(app).loadActive().getOrThrow()!!.plannedSets.first().performedReps)
    }
    @Test fun explicitManualEntryIsNotOverwrittenByNativeAnalysis() {
        val (activity, active) = restoredCompleted(6); record(activity, active)
        nativeCount("background-session", 12); FinishedWorkoutStore(app).reconcile("background-session")
        assertProjectedSixReps(ExerciseHistoryStore(app).load().last("external_rotation")!!)
        assertEquals(30, WorkoutLogStore(app).load().single().volumeKg)
    }
    @Test fun separateSessionsOnTheSameDayRemainSeparate() {
        val one = watchSnapshot("one"); FinishedWorkoutStore(app).finish(one, "어깨전문", 12)
        val two = watchSnapshot("two"); FinishedWorkoutStore(app).finish(two, "어깨전문", 15)
        assertEquals(2, ExerciseHistoryStore(app).load().recent("external_rotation").size)
        assertEquals(2, WorkoutLogStore(app).load().size)
        nativeCount("one", 6); FinishedWorkoutStore(app).reconcile("one")
        assertEquals("two", ExerciseHistoryStore(app).load().last("external_rotation")!!.sessionId)
    }
    @Test fun interruptedProjectionsRecoverWithTheirOriginalDate() {
        val original = watchSnapshot(); val finished = FinishedWorkoutStore(app); finished.finish(original, "어깨전문", 12)
        val prefs = app.getSharedPreferences("finished-workouts", Context.MODE_PRIVATE)
        val key = "session:${original.sessionId}"
        val json = org.json.JSONObject(prefs.getString(key, null)!!).put("projected", false)
        prefs.edit().putString(key, json.toString()).commit()
        app.getSharedPreferences("exercise-history", Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences("workout-log", Context.MODE_PRIVATE).edit().clear().commit()
        finished.replay(); finished.replay()
        assertEquals(1, WorkoutLogStore(app).load().size)
        assertEquals(0L, WorkoutLogStore(app).load().single().epochDay)
        assertEquals(0L, ExerciseHistoryStore(app).load().last("external_rotation")!!.epochDay)
    }

    @Test fun lateAnalysisRemovesAnInflatedRepetitionRecord() {
        ExerciseHistoryStore(app).add(ExerciseLog("external_rotation", 0, listOf(LoggedSet(5.0, 8)), "earlier"))
        val original = watchSnapshot(); val store = FinishedWorkoutStore(app); store.finish(original, "어깨전문", 12)
        assertTrue(store.recordHits(original.sessionId).any { it.second.kind == RecordKind.SAME_WEIGHT_REPS })
        nativeCount(original.sessionId, 6); store.reconcile(original.sessionId)
        assertTrue(store.recordHits(original.sessionId).isEmpty())
    }

    @Test fun freeWorkoutIsRecoverableWithoutADefaultRoutineEntry() {
        val store = SessionStore(app); store.saveActive(snapshot().copy(routineId = "free"))
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        MainActivity::class.java.getDeclaredField("sessionStore").apply { isAccessible = true }.set(activity, store)
        assertNotNull(MainActivity::class.java.getDeclaredMethod("restoreActiveWorkout").apply { isAccessible = true }.invoke(activity))
    }
    @Test fun changedExerciseIsRestoredFromTheSnapshotRatherThanTheDefaultTemplate() {
        val store = SessionStore(app)
        store.saveActive(snapshot().copy(plannedSets = listOf(PlannedSetSnapshot(0, "flat_bench", 0, 6, 60.0, false, exerciseName = "벤치 프레스"))))
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        MainActivity::class.java.getDeclaredField("sessionStore").apply { isAccessible = true }.set(activity, store)
        val restored = MainActivity::class.java.getDeclaredMethod("restoreActiveWorkout").apply { isAccessible = true }.invoke(activity)!!
        val routine = restored.javaClass.getDeclaredField("routine").apply { isAccessible = true }.get(restored) as WorkoutRoutine
        assertEquals("flat_bench", routine.exercises.first().exercise.id)
        assertEquals(60.0, routine.exercises.first().sets.first().weightKg!!, 0.001)
        assertEquals(1, routine.exercises.size)
    }

    @Test fun customExerciseMetadataAndCompletedValuesSurviveRoutineRecovery() {
        val set = PlannedSetSnapshot(0, "custom-local", 0, 10, null, true,
            exerciseName = "직접 만든 운동", exerciseRestSeconds = 60, restOverrideSeconds = 45,
            performedReps = 6, repSource = SetRepSource.MANUAL, exerciseMuscles = "등",
            bodyCategory = "등", equipment = "맨몸", pattern = "pull", family = "custom", gripType = "언더", rpe = "8")
        val saved = snapshot().copy(routineId = "free", routineName = "내 운동", plannedSets = listOf(set))
        val restored = restoreWorkoutRoutine(WorkoutSnapshotCodec.decode(WorkoutSnapshotCodec.encode(saved)), null)
        assertEquals("내 운동", restored.name)
        assertEquals("맨몸", restored.exercises.single().exercise.equipment)
        assertEquals("등", restored.exercises.single().exercise.bodyCategory)
        assertEquals(60, restored.exercises.single().restSeconds)
        assertEquals(45, restored.exercises.single().sets.single().restSeconds)
        assertEquals(6, restored.exercises.single().sets.single().performedReps)
        assertEquals(10, restored.exercises.single().sets.single().targetReps)
        assertEquals("언더", restored.exercises.single().sets.single().gripType)
        assertEquals("8", restored.exercises.single().sets.single().rpe)
    }

    @Test fun completedRowAndVolumeDisplayActualRepsWhileKeepingTheTarget() {
        val (activity, active) = restoredCompleted(6)
        val model = MainActivity::class.java.getDeclaredMethod("buildWorkoutModel", active.javaClass)
            .apply { isAccessible = true }.invoke(activity, active) as dev.kyungmin.kcoach.phone.ui.WorkoutUiModel
        val exercise = model.exercises.single()
        assertEquals(6, exercise.sets.first().reps)
        assertEquals(30, exercise.volumeDone)
        assertEquals(20, exercise.targetReps)
    }
    @Test fun editingAndUndoingACompletedSetUsesActualRepsAndRestoresItsPosition() {
        val (activity, active) = restoredCompleted(6)
        MainActivity::class.java.getDeclaredField("activeWorkout").apply { isAccessible = true }.set(activity, active)
        val actions = MainActivity::class.java.getDeclaredField("workoutActions").apply { isAccessible = true }
            .get(activity) as dev.kyungmin.kcoach.phone.ui.WorkoutActions
        actions.onEditValue(0, 0, dev.kyungmin.kcoach.phone.ui.SetField.REPS, "4", false)
        MainActivity::class.java.getDeclaredMethod("persistActiveWorkout", active.javaClass).apply { isAccessible = true }.invoke(activity, active)
        val saved = SessionStore(app).loadActive().getOrThrow()!!
        assertEquals(10, saved.plannedSets.first().targetReps)
        assertEquals(4, saved.plannedSets.first().performedReps)
        assertEquals(4, saved.performedReps["external_rotation"])
        actions.onToggleSet(0, 0)
        val undone = SessionStore(app).loadActive().getOrThrow()!!
        assertEquals(0, undone.setIndex)
        assertEquals(0, undone.performedReps["external_rotation"])
        assertNull(undone.plannedSets.first().performedReps)
    }

    @Test fun undoWhilePausedDoesNotResumeTheWatch() {
        val (activity, active) = restoredCompleted(6)
        MainActivity::class.java.getDeclaredField("activeWorkout").apply { isAccessible = true }.set(activity, active)
        active.javaClass.getDeclaredField("paused").apply { isAccessible = true }.setBoolean(active, true)
        val actions = MainActivity::class.java.getDeclaredField("workoutActions").apply { isAccessible = true }
            .get(activity) as dev.kyungmin.kcoach.phone.ui.WorkoutActions
        actions.onToggleSet(0, 0)
        val saved = SessionStore(app).loadActive().getOrThrow()!!
        assertTrue(saved.paused)
        assertEquals(WorkoutPhase.PAUSED, saved.phase)
        assertEquals("PAUSED", WearSync.parse(PhoneCommandOutbox(app).pending()!!)[11])
    }

}
