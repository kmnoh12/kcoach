package dev.kyungmin.kcoach.phone

import android.content.Context
import dev.kyungmin.kcoach.core.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AdversarialReviewTest {
    private val app get() = RuntimeEnvironment.getApplication()
    @Test fun retryableCommandAckMustRetainTheCommand() {
        val outbox = PhoneCommandOutbox(app)
        val stamped = outbox.enqueue(WearSync.command("SET", "review-session", "external_rotation", 0, 10))
        val revision = WearSync.parse(stamped)[10]
        outbox.acknowledge("1|review-session|$revision|WAITING_COMPLETION")
        assertEquals("retryable rejection deleted command", stamped, outbox.pending())
    }

    @Test fun measureFinishedSessionReplayCost() {
        fun seed(n: Int) {
            val finished = app.getSharedPreferences("finished-workouts", Context.MODE_PRIVATE).edit().clear()
            val analyses = app.getSharedPreferences("wear-sync", Context.MODE_PRIVATE).edit().clear()
            repeat(n) { i ->
                val session = "review-$i"
                val snapshot = WorkoutSnapshot(sessionId=session,routineId="r",exerciseIndex=0,setIndex=0,
                    plannedSets=listOf(PlannedSetSnapshot(0,"external_rotation",0,10,5.0,true,performedReps=6,repSource=SetRepSource.PHONE_ANALYSIS)),
                    performedReps=mapOf("external_rotation" to 6),phase=WorkoutPhase.WORKING,restEndAtMs=0,pausedRestRemainingMs=0,
                    paused=false,startedAtMs=1,pausedSinceMs=0,totalPausedMs=0,lastSequence=0,lastCompletedSetKey=null,captureStates=emptyMap())
                finished.putString("session:$session", JSONObject().put("snapshot", WorkoutSnapshotCodec.encode(snapshot))
                    .put("title", "review").put("minutes", 10).put("epochDay", 1).put("projected", true).toString())
                val manifest = WireEnvelope(messageType=WireMessageType.CAPTURE_MANIFEST,sessionId=session,source=WireSource.WATCH,
                    sentAtElapsedMs=0,sentAtWallMs=0,payload=mapOf(WirePayloadKeys.CAPTURE_ID to "cap-$i", WirePayloadKeys.EXERCISE_ID to "external_rotation",WirePayloadKeys.SET_INDEX to "0"))
                val result=MutableList(24){""}.apply { this[0]="6";this[1]="6";this[2]="6";this[11]="0.9" }
                analyses.putString("analysis:cap-$i", result.joinToString("|"))
                analyses.putString(CaptureInbox.manifestKey("cap-$i"), WireCodec.encode(manifest))
            }
            finished.commit();analyses.commit()
        }
        seed(20);FinishedWorkoutStore(app).replay()
        for (n in listOf(100,500,1000)) {
            seed(n)
            val begin=System.nanoTime();FinishedWorkoutStore(app).replay()
            println("REVIEW_REPLAY sessions=$n elapsedMs=${(System.nanoTime()-begin)/1_000_000}")
            val warm = System.nanoTime()
            assertEquals("already projected unchanged sessions must not be reconciled again", 0, FinishedWorkoutStore(app).replay())
            println("REVIEW_REPLAY_WARM sessions=$n elapsedMs=${(System.nanoTime()-warm)/1_000_000}")
        }
    }
}
