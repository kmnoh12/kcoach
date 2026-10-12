package dev.kyungmin.kcoach.phone

import android.content.*
import dev.kyungmin.kcoach.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream


@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AnalysisRecoveryTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private fun snapshot() = WorkoutSnapshot(sessionId="audit", routineId="shoulders", exerciseIndex=0, setIndex=0,
        plannedSets=listOf(PlannedSetSnapshot(0,"external_rotation",0,10,5.0,false), PlannedSetSnapshot(0,"external_rotation",1,10,5.0,false)),
        performedReps=emptyMap(), phase=WorkoutPhase.WORKING, restEndAtMs=0, pausedRestRemainingMs=0, paused=false,
        startedAtMs=1, pausedSinceMs=0, totalPausedMs=0, lastSequence=0, lastCompletedSetKey=null, captureStates=emptyMap())
    private fun fixture(): Pair<WireEnvelope,ByteArray> {
        val records=listOf(SensorCaptureRecord(SensorSample(1_000L,0f,0f,9.8f,1f,0f,0f)), SensorCaptureRecord(SensorSample(2_000L,0f,0f,9.8f,1f,0f,0f)))
        val out=ByteArrayOutputStream(); SensorCaptureFormat.write(out,records,50.0); val bytes=out.toByteArray()
        val m=WireMessages.captureManifest(sessionId="audit",exerciseSessionId="external_rotation",setId="set0",captureId="audit-capture",
            formatVersion=SensorCaptureFormat.FORMAT_VERSION,compression="NONE",sampleCount=2,sampleRateRequestedHz=50.0,sampleRateObservedHz=50.0,
            startMonotonicNs=1_000L,endMonotonicNs=2_000L,sha256=SensorCaptureFormat.sha256(bytes),assetSizeBytes=bytes.size.toLong(),
            watchDetectedReps=10,exerciseId="external_rotation",exerciseName="외회전",setIndex=0)
        return m to bytes
    }
    @Test fun failedAnalysisCanRetryAfterServiceRestart() {
        val inbox=CaptureInbox(app,app.getSharedPreferences("wear-sync",Context.MODE_PRIVATE)); val (m,b)=fixture()
        inbox.receive(m,b).getOrThrow(); assertEquals(1,inbox.pendingAnalysis().size)
        inbox.markState("audit-capture",CaptureState.ANALYSIS_FAILED,"temporary failure")
        inbox.markAnalysisAttempted("audit-capture",false)
        assertEquals(1,CaptureInbox(app,app.getSharedPreferences("wear-sync",Context.MODE_PRIVATE)).pendingAnalysis().size)
    }
    @Test fun unknownConfirmedCountStaysUnknownInBroadcast() {
        val inbox=CaptureInbox(app,app.getSharedPreferences("wear-sync",Context.MODE_PRIVATE)); val (m,b)=fixture()
        val received=inbox.receive(m,b).getOrThrow()
        assertNull(PhoneRepAnalyzer.analyze(received.validated.document,"external_rotation","외회전",10).result.confirmedReps)
        var captured:Intent?=null
        val receiver=object:BroadcastReceiver(){override fun onReceive(context:Context,intent:Intent){captured=intent}}
        app.registerReceiver(receiver,IntentFilter(PhoneWearListenerService.ACTION_CAPTURE),Context.RECEIVER_NOT_EXPORTED)
        try {
            val listener=Robolectric.buildService(PhoneWearListenerService::class.java).get()
            PhoneWearListenerService::class.java.getDeclaredMethod("analyzeAndPersistCapture",ReceivedCapture::class.java)
                .apply {isAccessible=true}.invoke(listener,received)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertNotNull(captured); assertEquals(-1,captured!!.getIntExtra("confirmedReps",-1))
        } finally {app.unregisterReceiver(receiver)}
    }
    @Test fun backgroundNativeAnalysisRestoresIntoActiveSession() {
        val inbox=CaptureInbox(app,app.getSharedPreferences("wear-sync",Context.MODE_PRIVATE)); val(m,b)=fixture()
        val received=inbox.receive(m,b).getOrThrow()
        val listener=Robolectric.buildService(PhoneWearListenerService::class.java).get()
        PhoneWearListenerService::class.java.getDeclaredMethod("analyzeAndPersistCapture",ReceivedCapture::class.java).apply{isAccessible=true}.invoke(listener,received)
        assertNotNull(app.getSharedPreferences("wear-sync",Context.MODE_PRIVATE).getString("analysis:audit-capture",null))
        val store=SessionStore(app);store.saveActive(snapshot())
        val activity=Robolectric.buildActivity(MainActivity::class.java).get()
        MainActivity::class.java.getDeclaredField("sessionStore").apply{isAccessible=true}.set(activity,store)
        val restored=MainActivity::class.java.getDeclaredMethod("restoreActiveWorkout").apply{isAccessible=true}.invoke(activity)
        val analyses=restored.javaClass.getDeclaredField("analyses").apply{isAccessible=true}.get(restored) as Map<*,*>
        assertEquals(1, analyses.size)
    }
    private fun analyze(manifest: WireEnvelope = fixture().first): SetAnalysisUi {
        val received = CaptureInbox(app, app.getSharedPreferences("wear-sync", Context.MODE_PRIVATE))
            .receive(manifest, fixture().second).getOrThrow()
        val listener = Robolectric.buildService(PhoneWearListenerService::class.java).get()
        PhoneWearListenerService::class.java.getDeclaredMethod("analyzeAndPersistCapture", ReceivedCapture::class.java)
            .apply { isAccessible = true }.invoke(listener, received)
        return SetAnalysisStore(app).loadForSession(manifest.sessionId).values.single()
    }
    private fun restoreResult(activity: MainActivity): Any = requireNotNull(MainActivity::class.java.getDeclaredMethod("restoreLastWorkoutResult")
        .apply { isAccessible = true }.invoke(activity))
    @Suppress("UNCHECKED_CAST")
    private fun analyses(result: Any): Map<String, SetAnalysisUi> = result.javaClass.getDeclaredField("analyses")
        .apply { isAccessible = true }.get(result) as Map<String, SetAnalysisUi>
    private fun saveResultHeader(session: String = "audit", rows: String = "") {
        fun encode(value: String) = android.util.Base64.encodeToString(value.toByteArray(), android.util.Base64.NO_WRAP)
        app.getSharedPreferences("workout_results", Context.MODE_PRIVATE).edit()
            .putString("last", "${encode(session)}#${encode("어깨전문")}#12#2#$rows").commit()
    }
    @Test fun lateBackgroundAnalysisRecoversIntoAnAlreadyCompletedWorkout() {
        saveResultHeader()
        analyze()
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        val result = restoreResult(activity)
        assertEquals(1, analyses(result).size)
        assertNull(analyses(result).values.single().confirmedReps)
    }
    @Test fun nullableCountsAndLateDriftSurviveResultRoundTripWithoutRawMetadata() {
        saveResultHeader()
        val analysis = analyze()
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        val result = restoreResult(activity)
        val original = analysis.copy(watchReps = null, phoneReps = 0, confirmedReps = null, lateDrift = 0.35)
        (analyses(result) as MutableMap)["external_rotation:0"] = original
        MainActivity::class.java.getDeclaredMethod("persistLastWorkoutResult", result.javaClass)
            .apply { isAccessible = true }.invoke(activity, result)
        app.getSharedPreferences("wear-sync", Context.MODE_PRIVATE).edit().clear().commit()
        assertEquals(original, analyses(restoreResult(activity)).values.single())
        assertEquals("0회", measuredRepsText(0, "확정 불가"))
        assertEquals("확정 불가", measuredRepsText(null, "확정 불가"))
    }
    @Test fun otherSessionAndCalibrationResultsDoNotAttachToTheLastWorkout() {
        analyze()
        assertTrue(SetAnalysisStore(app).loadForSession("other-session").isEmpty())
        saveResultHeader("other-session")
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        assertTrue(analyses(restoreResult(activity)).isEmpty())
        val prefs = app.getSharedPreferences("wear-sync", Context.MODE_PRIVATE)
        val manifest = fixture().first.copy(sessionId = "calibration-audit")
        prefs.edit().putString(CaptureInbox.manifestKey("audit-capture"), WireCodec.encode(manifest)).commit()
        assertTrue(SetAnalysisStore(app).loadForSession(manifest.sessionId).isEmpty())
    }
    @Test fun corruptCaptureDoesNotHideAnotherValidAnalysis() {
        analyze()
        val prefs = app.getSharedPreferences("wear-sync", Context.MODE_PRIVATE)
        prefs.edit().putString("analysis:broken", "broken")
            .putString(CaptureInbox.manifestKey("broken"), "not-a-manifest").commit()
        assertEquals(1, SetAnalysisStore(app).loadForSession("audit").size)
    }
    @Test fun oldEighteenFieldWorkoutResultStillLoads() {
        val row = listOf("external_rotation", "외회전", "0", "10", "9", "9", "0.8", "WRIST_IMU_CAUTION",
            "generic-wrist", "", "C_PERSONALIZED_AUTO_COUNT_BETA", "", "", "", "90", "0.9", "1.2", "0.7")
            .joinToString(",") { android.util.Base64.encodeToString(it.toByteArray(), android.util.Base64.NO_WRAP) }
        saveResultHeader(rows = row)
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        val analysis = analyses(restoreResult(activity)).values.single()
        assertEquals(9, analysis.confirmedReps)
        assertEquals(90, analysis.tempoConsistency)
        assertNull(analysis.lateDrift)
    }
    @Test fun successfulAnalysisIsNotRetriedAndUnknownHeartRateIsNotNumeric() {
        val inbox = CaptureInbox(app, app.getSharedPreferences("wear-sync", Context.MODE_PRIVATE))
        val (manifest, bytes) = fixture(); inbox.receive(manifest, bytes).getOrThrow()
        inbox.markAnalysisAttempted("audit-capture", true)
        assertTrue(inbox.pendingAnalysis().isEmpty())
        assertEquals("심박 측정 대기", heartRateText(0))
        assertEquals("심박 48bpm", heartRateText(48))
    }

    @Test fun countRowsDistinguishUnknownZeroAndManualEntry() {
        val unknown = SetAnalysisUi("external_rotation", "외회전", 0, null, 0, null, 0.0)
        assertEquals(listOf("미측정", "0회", "확정 불가"), unknown.repMetricRows().map { it.second })
        assertEquals("수동 입력 필요", unknown.copy(trackingMode = "MANUAL_REQUIRED").repMetricRows().last().second)
    }

    @Test fun lowQualityNativeAnalysisNeverReplacesTheAlreadySavedWatchCount() {
        val saved = snapshot().copy(plannedSets = listOf(PlannedSetSnapshot(0, "pull_up", 0, 10, 5.0, true,
            performedReps = 6, repSource = SetRepSource.WATCH)), performedReps = mapOf("pull_up" to 6))
        SessionStore(app).saveActive(saved)
        val records = buildList {
            var t = 1_000_000L
            repeat(3) { for (rate in listOf(1.2f, 0f, -1.2f, 0f)) repeat(20) {
                add(SensorCaptureRecord(SensorSample(t, 0f, 0f, 9.8f, rate, 0f, 0f)))
                t += 50_000_000L
            } }
        }
        val bytes = ByteArrayOutputStream().also { SensorCaptureFormat.write(it, records, 50.0) }.toByteArray()
        val manifest = WireMessages.captureManifest("audit", "pull_up", "set", "poor-quality", 1, "NONE", records.size,
            50.0, 20.0, records.first().sample.timestampNs, records.last().sample.timestampNs, SensorCaptureFormat.sha256(bytes),
            bytes.size.toLong(), watchDetectedReps = 6, exerciseId = "pull_up", exerciseName = "pull", setIndex = 0)
        val raw = CaptureInbox(app, app.getSharedPreferences("wear-sync", Context.MODE_PRIVATE)).receive(manifest, bytes).getOrThrow()
        val listener = Robolectric.buildService(PhoneWearListenerService::class.java).get()
        PhoneWearListenerService::class.java.getDeclaredMethod("analyzeAndPersistCapture", ReceivedCapture::class.java)
            .apply { isAccessible = true }.invoke(listener, raw)
        val result = SetAnalysisStore(app).loadForSession("audit").values.single()
        assertEquals(3, result.phoneReps); assertNull(result.confirmedReps)
        assertEquals(6, SessionStore(app).loadActive().getOrThrow()!!.plannedSets.single().performedReps)
        assertTrue(raw.rawFile.isFile)
    }

}
