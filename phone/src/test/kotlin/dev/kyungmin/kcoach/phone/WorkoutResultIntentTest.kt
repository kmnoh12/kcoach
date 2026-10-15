package dev.kyungmin.kcoach.phone

import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.os.Bundle
import android.net.Uri
import android.content.pm.PackageManager
import android.util.Base64
import android.widget.FrameLayout
import dev.kyungmin.kcoach.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorkoutResultIntentTest {
    private val app: Context get() = RuntimeEnvironment.getApplication()
    private val action = "dev.kyungmin.kcoach.action.SHOW_WORKOUT_RESULT"
    private val extra = "workoutResultSessionId"

    private fun snapshot(id: String, started: Long = 1000, done: Boolean = true) = WorkoutSnapshot(
        sessionId = id, routineId = "routine", exerciseIndex = 0, setIndex = 0,
        plannedSets = listOf(PlannedSetSnapshot(0, "dumbbell_curl", 0, 8, 10.0, done,
            exerciseName = "덤벨 컬", performedReps = if (done) 8 else null,
            repSource = if (done) SetRepSource.MANUAL else null)),
        performedReps = emptyMap(), phase = WorkoutPhase.WORKING, restEndAtMs = 0, pausedRestRemainingMs = 0,
        paused = false, startedAtMs = started, pausedSinceMs = 0, totalPausedMs = 0, lastSequence = 0,
        lastCompletedSetKey = null, captureStates = emptyMap())

    private fun fixture() {
        FinishedWorkoutStore(app).finish(snapshot("requested", 1000), "요청한 운동", 12)
        FinishedWorkoutStore(app).finish(snapshot("latest", 2000), "가장 최근 운동", 20)
        fun encode(value: String) = Base64.encodeToString(value.toByteArray(), Base64.NO_WRAP)
        app.getSharedPreferences("workout_results", Context.MODE_PRIVATE).edit().putString("last",
            listOf(encode("latest"), encode("가장 최근 운동"), "20", "1", "").joinToString("#")).commit()
    }
    private fun request(id: String) = Intent(app, MainActivity::class.java).setAction(action).putExtra(extra, id)
    private fun field(activity: MainActivity, name: String): Any? =
        MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)
    private fun call(activity: MainActivity, name: String) =
        MainActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity)
    private fun savedLatest() = app.getSharedPreferences("workout_results", Context.MODE_PRIVATE).getString("last", null)

    @Test fun theLocalFactoryTargetsMainAndRequestsReuseWithTheDocumentedSessionId() {
        val intent = WorkoutResultIntent.create(app, "requested")
        assertEquals(action, intent.action)
        assertEquals("requested", intent.getStringExtra(extra))
        assertEquals(MainActivity::class.java.name, intent.component!!.className)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP, intent.flags)
        assertEquals("requested", WorkoutResultIntent.sessionId(intent))
        try { WorkoutResultIntent.create(app, "../invalid"); fail("Invalid session ID") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun aRemoteUriResolvesAndOpensTheRequestedResultWithoutIntentExtras() {
        fixture()
        val remote = WorkoutResultIntent.createRemoteView("requested")
        val delivered = Intent(Intent.ACTION_VIEW, remote.data).addCategory(Intent.CATEGORY_BROWSABLE)
        assertNull(delivered.extras)
        val resolved = app.packageManager.resolveActivity(delivered, PackageManager.MATCH_DEFAULT_ONLY)
        assertEquals(MainActivity::class.java.name, resolved!!.activityInfo.name)
        val latest = savedLatest()
        val controller = Robolectric.buildActivity(MainActivity::class.java, delivered).create().start().resume()
        try {
            assertEquals("requested", field(controller.get(), "visibleWorkoutResultSessionId"))
            assertEquals(latest, savedLatest())
        } finally { controller.pause().stop().destroy() }
        assertEquals("requested", WorkoutResultIntent.sessionId(delivered.putExtra(extra, "latest")))
    }

    @Test fun coldLaunchOpensTheRequestedSavedSessionWithoutReplacingTheLatestRecord() {
        fixture()
        val latest = savedLatest()
        val controller = Robolectric.buildActivity(MainActivity::class.java, request("requested")).create().start().resume()
        try {
            assertEquals("requested", field(controller.get(), "visibleWorkoutResultSessionId"))
            assertEquals("RECORDS", field(controller.get(), "currentTab").toString())
            assertEquals(latest, savedLatest())
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun aNewIntentOpensTheRequestedResultFromAnotherTabAndSurvivesResume() {
        fixture()
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        try {
            call(controller.get(), "showMenu")
            controller.newIntent(request("requested"))
            assertEquals("requested", field(controller.get(), "visibleWorkoutResultSessionId"))
            (field(controller.get(), "wearReceiver") as BroadcastReceiver).onReceive(controller.get(),
                Intent(PhoneWorkoutService.ACTION_SESSION_CHANGED).putExtra("recordsRecovered", true))
            assertEquals("requested", field(controller.get(), "visibleWorkoutResultSessionId"))
            controller.pause().stop().start().resume()
            assertEquals("requested", field(controller.get(), "visibleWorkoutResultSessionId"))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun theVisibleRequestedResultSurvivesActivityRecreation() {
        fixture()
        val controller = Robolectric.buildActivity(MainActivity::class.java, request("requested")).create().start().resume()
        try {
            assertEquals("requested", field(controller.get(), "visibleWorkoutResultSessionId"))
            controller.recreate()
            assertEquals("requested", field(controller.get(), "visibleWorkoutResultSessionId"))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun aFreshUserRequestStillOpensTheSameSessionAfterProcessStateWasSavedOnHome() {
        fixture()
        val state = Bundle()
        val first = Robolectric.buildActivity(MainActivity::class.java, WorkoutResultIntent.create(app, "requested"))
            .create().start().resume()
        try {
            assertEquals("requested", field(first.get(), "visibleWorkoutResultSessionId"))
            call(first.get(), "showHome")
            first.saveInstanceState(state)
        } finally { first.pause().stop().destroy() }
        val second = Robolectric.buildActivity(MainActivity::class.java, WorkoutResultIntent.create(app, "requested"))
            .create(state).start().resume()
        try {
            assertEquals("requested", field(second.get(), "visibleWorkoutResultSessionId"))
        } finally { second.pause().stop().destroy() }
    }

    @Test fun navigatingAwayConsumesTheOldRequestInsteadOfReopeningItOnRecreation() {
        fixture()
        val controller = Robolectric.buildActivity(MainActivity::class.java, request("requested")).create().start().resume()
        try {
            assertEquals("requested", field(controller.get(), "visibleWorkoutResultSessionId"))
            call(controller.get(), "showHome")
            controller.recreate()
            assertEquals("HOME", field(controller.get(), "currentTab").toString())
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun consecutiveResultRequestsAreNotReplayedAfterReturningHomeAndRecreating() {
        fixture()
        val controller = Robolectric.buildActivity(MainActivity::class.java, WorkoutResultIntent.create(app, "requested"))
            .create().start().resume()
        try {
            controller.newIntent(WorkoutResultIntent.create(app, "latest"))
            assertEquals("latest", field(controller.get(), "visibleWorkoutResultSessionId"))
            call(controller.get(), "showHome")
            controller.recreate()
            assertEquals("HOME", field(controller.get(), "currentTab").toString())
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun unknownMalformedAndWrongTypeRequestsDoNotFallBackToTheLatestResult() {
        fixture()
        val latest = savedLatest()
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        try {
            call(controller.get(), "showMenu")
            val root = field(controller.get(), "root") as FrameLayout
            val displayed = root.getChildAt(0)
            for (intent in listOf(request("missing"), request("../invalid"), request(""),
                Intent(app, MainActivity::class.java).setAction(action).putExtra(extra, 7),
                Intent(Intent.ACTION_VIEW, Uri.parse("kcoach://workout/result?session_id=requested&session_id=latest")),
                Intent(Intent.ACTION_VIEW, Uri.parse("kcoach://chatgpt/return?session_id=requested")),
                Intent(Intent.ACTION_VIEW, Uri.parse("https://workout/result?session_id=requested")),
                Intent(Intent.ACTION_VIEW, Uri.parse("kcoach://workout/result/extra?session_id=requested")))) {
                controller.newIntent(intent)
                assertEquals("MENU", field(controller.get(), "currentTab").toString())
                assertSame(displayed, root.getChildAt(0))
                assertNull(field(controller.get(), "visibleWorkoutResultSessionId"))
            }
            assertEquals(latest, savedLatest())
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun openingAFinishedResultDoesNotStopOrRewriteAnActiveWorkout() {
        fixture()
        val active = snapshot("still-running", 3000, done = false)
        SessionStore(app).saveActive(active)
        val pending = PhoneCommandOutbox(app).enqueue(WearSync.command("SET", active.sessionId, "dumbbell_curl", 0, 8))
        val revision = app.getSharedPreferences("workout-session", Context.MODE_PRIVATE).getLong("revision", 0)
        val controller = Robolectric.buildActivity(MainActivity::class.java, request("requested")).create().start().resume()
        try {
            assertEquals("requested", field(controller.get(), "visibleWorkoutResultSessionId"))
            assertEquals(active, SessionStore(app).loadActive().getOrThrow())
            assertEquals(revision, app.getSharedPreferences("workout-session", Context.MODE_PRIVATE).getLong("revision", 0))
            assertEquals(pending, PhoneCommandOutbox(app).pending())
            // An in-progress session must never be manufactured into a finished result.
            controller.newIntent(request(active.sessionId))
            assertEquals("requested", field(controller.get(), "visibleWorkoutResultSessionId"))
            assertEquals(active, SessionStore(app).loadActive().getOrThrow())
        } finally { controller.pause().stop().destroy() }
    }
}
