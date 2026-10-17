package dev.kyungmin.kcoach.wear

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

/** Screens 3, 7-12 rendered from real state broadcasts, plus the transitions between them. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w216dp-h216dp-round-xhdpi")
class WatchMomentScreensTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun visibleTexts(root: View) = descendants(root).filterIsInstance<TextView>()
        .filter { it.isShown && it.text.isNotEmpty() }.map { it.text.toString().replace(Moment.GAP, "") }
    private fun measure(root: View) {
        root.measure(View.MeasureSpec.makeMeasureSpec(432, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(432, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 432, 432)
    }
    private fun shot(root: View, name: String) {
        measure(root)
        val bitmap = Bitmap.createBitmap(432, 432, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val file = File("build/outputs/watch-preview/$name.png").apply { parentFile?.mkdirs() }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun idleUntil(uptime: Long) { val wait = uptime - SystemClock.uptimeMillis(); if (wait > 0) idle(wait) }
    private fun idle(ms: Long = 0) {
        if (ms > 0) shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms)) else shadowOf(Looper.getMainLooper()).idle()
    }
    private fun state(session: String = "preview", setId: String = "set-1", running: Boolean = false, resting: Boolean = false,
        paused: Boolean = false, setIndex: Int = 0, reps: Int = 12, target: Int = 12, weight: Double = 20.0, name: String = "벤치 프레스",
        phase: String = "WORKING", restEndAtMs: Long = 0L, heartRate: Int = 0, extras: Intent.() -> Unit = {}) {
        app.sendBroadcast(Intent(WearWorkoutService.ACTION_STATE).setPackage(app.packageName)
            .putExtra("session", session).putExtra("setId", setId).putExtra("attemptId", "a-$setId")
            .putExtra("exerciseId", "bench").putExtra("exerciseName", name).putExtra("paired", true)
            .putExtra("running", running).putExtra("resting", resting).putExtra("paused", paused)
            .putExtra("setIndex", setIndex).putExtra("reps", reps).putExtra("targetReps", target).putExtra("weightKg", weight)
            .putExtra("heartRate", heartRate).putExtra("restSeconds", 60).putExtra("restEndAtMs", restEndAtMs)
            .putExtra("phase", phase).apply(extras))
        idle()
    }
    private fun start(): Pair<org.robolectric.android.controller.ActivityController<MainActivity>, View> {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val root = controller.get().findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        measure(root)
        return controller to root
    }
    private fun setDone(root: View) = descendants(root).filterIsInstance<SetDoneView>().single()
    private fun layerShown(root: View) = (setDone(root).parent as View).visibility == View.VISIBLE

    @Test fun aFinishedSetShowsTheCheckForThreeAndAHalfSecondsWhileTheRestClockAlreadyRuns() {
        val (controller, root) = start()
        state(running = true, reps = 11)
        state(running = true, reps = 12)
        val endedAt = SystemClock.uptimeMillis()
        state(running = false, reps = 12, phase = "READY")                 // the watch counted the set to its end
        assertTrue(layerShown(root))
        setDone(root).check.seek(400f); shot(root, "set-done-drawing")
        setDone(root).check.seek(1_200f); shot(root, "set-done-pop")
        setDone(root).check.finish()
        idleUntil(endedAt + 2_500); shot(root, "set-done")
        val texts = visibleTexts(setDone(root))
        assertTrue("$texts", texts.containsAll(listOf("1세트 완료", "12회 · 20kg")))
        assertFalse("Without a record there is no record line", texts.any { it.contains("갱신") })
        assertEquals(SetDoneView.Look.DONE, setDone(root).look)
        // The phone starts the rest a moment later; its clock counts from the end of the set.
        val restEnd = System.currentTimeMillis() + 60_000L - 3_500L
        state(setId = "set-2", resting = true, setIndex = 1, reps = 12, phase = "RESTING", restEndAtMs = restEnd)
        assertTrue("A rest state must not cut the moment short", layerShown(root))
        idleUntil(endedAt + 3_300)
        assertTrue(layerShown(root))
        idleUntil(endedAt + 3_500 + 600)
        assertFalse("After 3.5 s the rest screen takes over", layerShown(root))
        val face = descendants(root).filterIsInstance<WatchWorkoutFace>().single()
        assertEquals("The rest clock kept running underneath", "0:57", face.value.text.toString())
        shot(root, "set-done-then-rest")
        controller.pause().stop().destroy()
    }

    @Test fun aHeavierLoadComparesTwoBarsAndMoreRepsLineUpDots() {
        val (controller, root) = start()
        state(running = true, reps = 5, weight = 62.5)
        state(running = false, reps = 5, weight = 62.5, phase = "READY") {
            putExtra("recordSetId", "set-1"); putExtra("recordKind", "HEAVIEST_WEIGHT")
            putExtra("recordWeightKg", 62.5); putExtra("recordPreviousWeightKg", 60.0)
        }
        idle(2_000); shot(root, "record-heaviest")
        assertEquals(SetDoneView.Look.HEAVIEST, setDone(root).look)
        val heavy = visibleTexts(setDone(root))
        listOf("최고 중량 갱신", "60", "62.5kg", "이전", "오늘", "벤치 프레스 · 1세트 완료").forEach { assertTrue("$it in $heavy", heavy.contains(it)) }
        assertTrue(heavy.contains("+2.5kg"))
        idle(2_000)
        state(setId = "set-2", running = true, setIndex = 1, reps = 3, weight = 62.5)
        state(setId = "set-2", running = true, setIndex = 1, reps = 11, weight = 62.5)
        state(setId = "set-2", running = false, setIndex = 1, reps = 11, weight = 62.5, phase = "READY") {
            putExtra("recordSetId", "set-2"); putExtra("recordKind", "SAME_WEIGHT_REPS")
            putExtra("recordWeightKg", 62.5); putExtra("recordReps", 11); putExtra("recordPreviousReps", 10)
        }
        idle(2_000); shot(root, "record-reps")
        assertEquals(SetDoneView.Look.REPS, setDone(root).look)
        val reps = visibleTexts(setDone(root))
        listOf("횟수 갱신", "62.5kg", "11회", "이전 10회 · +1회", "벤치 프레스 · 2세트 완료").forEach { assertTrue("$it in $reps", reps.contains(it)) }
        controller.pause().stop().destroy()
    }

    @Test fun aRecordReportedAfterTheSetEndedReplacesTheCheckAndANewSetEndsTheMoment() {
        val (controller, root) = start()
        state(running = true, reps = 8)
        state(running = false, reps = 8, phase = "READY")
        assertEquals(SetDoneView.Look.DONE, setDone(root).look)
        idle(1_000)
        // The phone's record for this set arrives with the rest command.
        state(setId = "set-2", resting = true, setIndex = 1, phase = "RESTING", restEndAtMs = System.currentTimeMillis() + 60_000) {
            putExtra("recordSetId", "set-1"); putExtra("recordKind", "SAME_WEIGHT_REPS")
            putExtra("recordWeightKg", 20.0); putExtra("recordReps", 8); putExtra("recordPreviousReps", 7)
        }
        idle(400)
        assertEquals(SetDoneView.Look.REPS, setDone(root).look)
        assertTrue(layerShown(root))
        // A record for some other set never shows here.
        state(setId = "set-2", resting = true, setIndex = 1, phase = "RESTING", restEndAtMs = System.currentTimeMillis() + 60_000) {
            putExtra("recordSetId", "other"); putExtra("recordKind", "HEAVIEST_WEIGHT")
            putExtra("recordWeightKg", 90.0); putExtra("recordPreviousWeightKg", 80.0)
        }
        assertEquals(SetDoneView.Look.REPS, setDone(root).look)
        state(setId = "set-2", running = true, setIndex = 1, reps = 0)
        assertFalse("Starting the next set ends the moment at once", layerShown(root))
        controller.pause().stop().destroy()
    }

    @Test fun aSubmittedCountAndExtremeValuesFitOnTheSetDoneScreen() {
        val (controller, root) = start()
        state(running = true, reps = 3, weight = 102.5, name = "케이블 익스터널 로테이션", setIndex = 11)
        var endedAt = SystemClock.uptimeMillis()
        state(running = false, reps = 105, weight = 102.5, name = "케이블 익스터널 로테이션", setIndex = 11, phase = "MANUAL_PENDING") {
            putExtra("recordSetId", "set-1"); putExtra("recordKind", "ESTIMATED_ONE_REP_MAX")
        }
        idleUntil(endedAt + 2_000); shot(root, "set-done-extreme")
        val texts = visibleTexts(setDone(root))
        assertTrue("$texts", texts.containsAll(listOf("12세트 완료", "105회 · 102.5kg", "추정 1RM 갱신")))
        idleUntil(endedAt + 4_500)
        state(setId = "set-2", running = true, setIndex = 12, reps = 1, weight = 102.5, name = "케이블 익스터널 로테이션")
        endedAt = SystemClock.uptimeMillis()
        state(setId = "set-2", running = false, setIndex = 12, reps = 31, weight = 102.5, name = "케이블 익스터널 로테이션", phase = "READY") {
            putExtra("recordSetId", "set-2"); putExtra("recordKind", "SAME_WEIGHT_REPS")
            putExtra("recordWeightKg", 102.5); putExtra("recordReps", 31); putExtra("recordPreviousReps", 28)
        }
        idleUntil(endedAt + 2_000); shot(root, "record-reps-extreme")
        assertTrue(visibleTexts(setDone(root)).containsAll(listOf("31회", "이전 28회 · +3회", "102.5kg")))
        idleUntil(endedAt + 4_500)
        state(setId = "set-3", running = true, setIndex = 13, reps = 1, weight = 102.5, name = "케이블 익스터널 로테이션")
        state(setId = "set-3", running = false, setIndex = 13, reps = 1, weight = 102.5, name = "케이블 익스터널 로테이션", phase = "READY") {
            putExtra("recordSetId", "set-3"); putExtra("recordKind", "HEAVIEST_WEIGHT")
            putExtra("recordWeightKg", 102.5); putExtra("recordPreviousWeightKg", 62.5)
        }
        idle(2_000); shot(root, "record-heaviest-extreme")
        assertTrue(visibleTexts(setDone(root)).containsAll(listOf("62.5", "102.5kg", "+40kg", "케이블 익스터널 로테이션 · 14세트 완료")))
        controller.pause().stop().destroy()
    }

    @Test fun calibrationCountsDownThenCountsRepsThenShowsTheResult() {
        val (controller, root) = start()
        fun calibration(running: Boolean, phase: String, reps: Int, extras: Intent.() -> Unit = {}) =
            state(session = "calibration-1", setId = "", running = running, reps = reps, target = 5, name = "덤벨 컬", phase = phase, extras = extras)
        val view = descendants(root).filterIsInstance<CalibrationView>().single()
        calibration(true, "BASELINE", 0) { putExtra("preparationHoldMs", 0L); putExtra("preparationHoldTargetMs", 2_000L); putExtra("phoneCameraRecording", true) }
        assertEquals(View.VISIBLE, view.visibility); assertEquals(CalibrationView.Stage.PREPARE, view.stage)
        assertEquals("2", view.countdown.text)
        shot(root, "calibration-prepare")
        calibration(true, "BASELINE", 0) { putExtra("preparationHoldMs", 1_200L); putExtra("preparationHoldTargetMs", 2_000L); putExtra("phoneCameraRecording", true) }
        assertEquals("1", view.countdown.text)
        idle(60); shot(root, "calibration-prepare-rolling")
        calibration(true, "CONTROLLED_REPS", 0) { putExtra("phoneCameraRecording", true) }
        calibration(true, "CONTROLLED_REPS", 3) { putExtra("phoneCameraRecording", true) }
        idle(500); shot(root, "calibration-count")
        assertEquals(CalibrationView.Stage.COUNT, view.stage); assertEquals("3", view.count.text)
        assertTrue(visibleTexts(view).containsAll(listOf("보정 · 목표 5회", "/ 5회", "평소처럼 천천히 해요", "폰 카메라 촬영 중")))
        val doneAt = SystemClock.uptimeMillis()
        calibration(false, "READY", 5) { putExtra("calibrationConfirmedReps", 5) }
        shot(root, "calibration-done")
        assertEquals(CalibrationView.Stage.DONE, view.stage)
        assertTrue(visibleTexts(view).containsAll(listOf("보정 완료", "5회", "센서", "확인", "일치", "폰에서 결과를 확인해요")))
        calibration(false, "READY", 5) { putExtra("calibrationConfirmedReps", 4) }
        shot(root, "calibration-done-mismatch")
        assertTrue(visibleTexts(view).contains("1회 차이"))
        calibration(false, "READY", 5)
        shot(root, "calibration-done-sensor-only")
        assertFalse(visibleTexts(view).contains("일치"))
        // The phone closes the session right away; the result still gets its 3.5 s.
        state(session = "", setId = "", phase = "IDLE", reps = 0)
        assertEquals(View.VISIBLE, view.visibility)
        idleUntil(doneAt + 3_600)
        assertEquals(View.GONE, view.visibility)
        controller.pause().stop().destroy()
    }

    @Test fun theWorkoutResultShowsWhatTheWatchMeasuredAndLeavesOutWhatItDidNot() {
        val tally = WatchSessionTally.get(app)
        val start = 1_000_000L
        // One state a second: set 1 (12 x 60 kg) for a minute, a rest, set 2 (10 x 62.5 kg), then rest until the end.
        for (second in 0..240) {
            val t = start + second * 1_000L
            when {
                second < 60 -> tally.observe("done-1", "s1", "a", true, false, false, second / 5, 60.0, 120, t)
                second == 60 -> tally.observe("done-1", "s1", "a", false, false, false, 12, 60.0, 120, t)
                second < 120 -> tally.observe("done-1", "s2", "a", false, true, false, 12, 62.5, 120, t)
                second < 180 -> tally.observe("done-1", "s2", "a", true, false, false, (second - 120) / 6 + 1, 62.5, 120, t)
                else -> tally.observe("done-1", "s3", "a", false, true, false, 10, 62.5, 120, t)
            }
        }
        tally.noteRecord("done-1", "bench")
        WatchHomeStore(app).complete("done-1", start + 2_530_000L)
        val (controller, root) = start()
        shot(root, "result")
        val texts = visibleTexts(descendants(root).filterIsInstance<WorkoutResultView>().single())
        listOf("42:10", "운동 시간", "2", "세트", "1,345", "볼륨 kg", "120", "평균 심박", "폰에서 자세히 보기").forEach { assertTrue("$it in $texts", texts.contains(it)) }
        assertTrue(texts.contains("오늘 깬 기록 1개"))
        controller.pause().stop().destroy()
    }

    /** The design sheet's own sample values, rendered for a side-by-side check against the sheet. */
    @Test fun designSampleValues() {
        app.getSharedPreferences("watch-home", android.content.Context.MODE_PRIVATE).edit()
            .putString("completedSession", "sample").putLong("completedAt", 1L).putBoolean("showCompletion", true)
            .putString("completedSummary", WatchHomeStore.Summary(2_530_000L, 18, 8_420.0, 121, 2).encode()).commit()
        val (controller, root) = start()
        shot(root, "sample-result")
        val texts = visibleTexts(descendants(root).filterIsInstance<WorkoutResultView>().single())
        assertTrue("$texts", texts.containsAll(listOf("42:10", "18", "8,420", "121", "오늘 깬 기록 2개")))
        state(session = "calibration-s", setId = "", running = true, reps = 0, target = 5, name = "덤벨 컬", phase = "BASELINE") {
            putExtra("preparationHoldMs", 0L); putExtra("preparationHoldTargetMs", 3_000L); putExtra("phoneCameraRecording", true)
        }
        shot(root, "sample-calibration-prepare")
        controller.pause().stop().destroy()
    }

    /** The screens that existed before, with the design sheet's sample values (screens 1, 2, 4, 4-2, 5, 6). */
    @Test fun designSampleValuesOnTheWorkoutPages() {
        WatchWorkoutOutbox(app).receivePhoneHello(listOf("1", "phone", "0", "WORKOUT_CONTROL_V1"))
        val (controller, root) = start()
        val pager = descendants(root).filterIsInstance<WatchPager>().single()
        state(name = "덤벨 컬", running = true, reps = 8, target = 12, weight = 20.0, heartRate = 112)
        shot(root, "sample-measure")
        pager.show(-1); shot(root, "sample-edit")
        pager.show(1); shot(root, "sample-aux"); pager.show(0)
        state(name = "덤벨 컬", running = true, reps = 13, target = 12, weight = 20.0, heartRate = 118)
        idle(400); shot(root, "sample-over")
        state(name = "덤벨 컬", setId = "set-2", resting = true, setIndex = 1, reps = 12, target = 12, weight = 20.0, phase = "RESTING",
            restEndAtMs = System.currentTimeMillis() + 44_600) { putExtra("restSeconds", 90) }
        idle(4_000); shot(root, "sample-rest")
        pager.show(-1); shot(root, "sample-rest-edit")
        controller.pause().stop().destroy()
    }

    @Test fun aResultWithoutHeartRateOrLoadLeavesThoseColumnsOut() {
        val tally = WatchSessionTally.get(app)
        tally.observe("done-2", "s1", "a", running = true, resting = false, paused = false, reps = 15, weightKg = null, heartRate = 0, nowMs = 10_000L)
        tally.observe("done-2", "s1", "a", running = false, resting = false, paused = false, reps = 15, weightKg = null, heartRate = 0, nowMs = 70_000L)
        WatchHomeStore(app).complete("done-2", 3_970_000L)
        val (controller, root) = start()
        shot(root, "result-sparse")
        val texts = visibleTexts(descendants(root).filterIsInstance<WorkoutResultView>().single())
        assertTrue(texts.contains("1:06:00")); assertTrue(texts.contains("세트"))
        assertFalse(texts.contains("볼륨 kg")); assertFalse(texts.contains("평균 심박")); assertFalse(texts.any { it.contains("기록") })
        controller.pause().stop().destroy()
    }
}
