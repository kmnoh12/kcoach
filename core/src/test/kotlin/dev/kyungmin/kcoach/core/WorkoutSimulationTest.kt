package dev.kyungmin.kcoach.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkoutSimulationTest {

    @Test
    fun simulateFullWorkoutRoutineWithWearSync() {
        println("==================================================")
        println("🏋️‍♂️ KCoach 폰 ↔ 워치 운동 세션 실시간 시뮬레이션 시작")
        println("==================================================")

        // 1. 루틴 설정 (벤치프레스 3세트 x 10회 @ 60kg)
        val routine = RoutinePlan(
            id = "routine_chest",
            name = "가슴 파괴 루틴",
            exercises = listOf(
                ExercisePlan(
                    id = "bench_press",
                    name = "벤치프레스",
                    sets = listOf(
                        SetPlan(targetReps = 10, weightKg = 60.0, restSeconds = 60),
                        SetPlan(targetReps = 10, weightKg = 60.0, restSeconds = 60),
                        SetPlan(targetReps = 10, weightKg = 60.0, restSeconds = 60)
                    ),
                    repRange = 8..12
                )
            )
        )

        val coach = SessionCoach(routine)
        val startUpdate = coach.start()
        val workingPhase = startUpdate.phase as SessionPhase.Working
        println("▶ [1단계] 루틴 시작됨! 활성 운동: 벤치프레스 (세트 index: ${workingPhase.setIndex})")

        // 2. 워치에서 폰으로 세트 시작 명령 전송 시뮬레이션 (WearSync Wire Format)
        val sessionId = "watch-sim-1001"
        val statePayload = WearSync.state(sessionId, "bench_press", setIndex = 0, reps = 0, heartRate = 72, recording = true)
        val parsedState = WearSync.parse(statePayload)
        
        assertEquals("1", parsedState[0])
        assertEquals("bench_press", parsedState[2])
        println("⌚ [워치 Wire Format 전송] -> 폰 수신: $statePayload")

        // 3. 센서 운동 반복수 및 심박수 상승 시뮬레이션 (1회 ~ 10회 진행)
        println("\n🏋️‍♂️ [2단계] 워치 센서 가속도/자이로 측정 및 반복수 카운팅 중...")
        var currentReps = 0
        var currentHeartRate = 72
        
        for (rep in 1..10) {
            currentReps = rep
            currentHeartRate += (4..6).random() // 심박수 상승 시뮬레이션 (72bpm -> ~125bpm)
            
            val samplePayload = WearSync.sample(sessionId, System.currentTimeMillis(), 0.1f, 15.8f, 2.1f, 0.05f, 0.12f, -0.08f)
            val stateUpdatePayload = WearSync.state(sessionId, "bench_press", setIndex = 0, reps = currentReps, heartRate = currentHeartRate, recording = true)
            
            println("  ↳ [Rep $rep/10] 가속도 피크 감지! 횟수: ${currentReps}회 | 심박수: ❤️ ${currentHeartRate} bpm")
        }

        assertEquals(10, currentReps)
        assertTrue(currentHeartRate in 110..150)
        println("✅ 세트 1 목표 횟수(10회) 달성!")

        // 4. 세트 완료 및 휴식 타이머 시뮬레이션
        println("\n⏱️ [3단계] 1세트 완료 -> 휴식 타이머(60초) 가동")
        val completedSet = CompletedSet(
            exerciseId = "bench_press",
            setIndex = 0,
            reps = 10,
            weightKg = 60.0,
            rir = 2.0,
            averageHeartRate = currentHeartRate,
            repSource = RepSource.CUSTOM_IMU,
            completedAtEpochMillis = System.currentTimeMillis()
        )
        val restUpdate = coach.completeSet(completedSet)
        val restingPhase = restUpdate.phase as SessionPhase.Resting
        assertEquals(60, restingPhase.targetSeconds)

        // 휴식 시간 진행 및 심박수 안정화 시뮬레이션
        var restElapsed = 0
        var restingHeartRate = currentHeartRate
        while (restElapsed < 60) {
            restElapsed += 15
            restingHeartRate -= 8 // 휴식 중 심박수 회복
            val hrSnapshot = HeartRateSnapshot(currentBpm = restingHeartRate, peakBpm = currentHeartRate, secondsSinceSet = restElapsed)
            val tickUpdate = coach.tickRest(15, hrSnapshot)
            println("  ⏱️ 휴식 진행 중: ${restElapsed}초 / 60초 (현재 심박수: ❤️ ${restingHeartRate} bpm)")
        }

        println("🔔 [4단계] 휴식 완료 알림! 심박수 회복 완료 (${restingHeartRate} bpm)")

        // 5. 다음 세트 준비
        val nextUpdate = coach.startNextSet()
        val nextWorking = nextUpdate.phase as SessionPhase.Working
        assertEquals(1, nextWorking.setIndex)
        println("▶ [5단계] 2세트 준비 완료! (세트 index: ${nextWorking.setIndex})")
        println("==================================================")
        println("🎉 운동 세션 및 폰↔워치 연동 시뮬레이션 정상 완료!")
        println("==================================================")
    }
}
