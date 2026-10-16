# 운동 중도 종료 저장 선택 / 하드 종료 폐기

브랜치: `codex1/discard-on-stop`. 이전 미커밋 변경을 검토·수정해서 이어갔다.
UI 파일, `confirmStopWorkout`, 뒤로가기 연결, `LoggedSet`, `ExerciseHistory`, `FinishedWorkoutStore`는 수정하지 않았다.
기존 안정성 보고서의 "최근 앱 닫기도 완료 세트 보존" 정책은 이 문서의 폐기 정책으로 대체된다.

## Claude 연결 API

```kotlin
// 저장하고 종료: 완료 세트만 기존 FinishedWorkoutStore로 저장.
WorkoutTermination(context).stop(saveCompleted = true)
// 저장 안 하고 종료: 완료 세트를 포함한 진행 세션 폐기.
WorkoutTermination(context).stop(saveCompleted = false)
// 성공 후 기존 방식으로 메모리의 activeWorkout/휴식 상태를 비우고 showHome().
// 두 경로 모두 durable STOP이 큐에 남으므로 전달 재시도 요청.
PhoneWorkoutService.retryPending(context)
```

반환형은 `Result<Unit>`. 실패하면 로그를 남기고 종료 journal을 유지하며, 성공으로 처리하지 않는다.
`stop()` 기본값은 `saveCompleted=true`로 기존 호출 호환성을 유지한다.
`MainActivity.confirmStopWorkout`은 HEAD와 완전히 같고 기존 `stop()` 호출이 남아 있다.
새 3선택 확인창(저장/폐기/계속 운동)의 표시·버튼 연결은 Claude 작업이다.
`nowMs`는 테스트용 선택 인자이며 기존 일시정지 제외 운동시간 계산을 유지한다.
저장 선택은 journal에도 기록되어 프로세스가 재시작돼도 같은 정책으로 재시도된다.
저장 선택의 완료 세트가 0개이면 빈 기록을 만들지 않고 종료한다.

## 종료 및 복구 기준

- 최근 앱 스와이프: `PhoneWorkoutService.onTaskRemoved`에서 명시적으로 `stop(false)` 호출.
  heartbeat/rest 콜백·알림·서비스 종료, durable STOP 재전송 방식 유지.
- 설정 강제 중지: Application 시작에서 최신 메인 프로세스 `ApplicationExitInfo`를 조회한다.
  활성 세션 시작 및 마지막 조회 이후의 `REASON_USER_REQUESTED`/`REASON_USER_STOPPED`만 폐기한다.
  다른 프로세스의 종료, 더 오래된 사용자 종료는 제외한다.
- 사용자 종료 사유를 먼저 확인해 미완료 저장 journal보다 하드 종료의 폐기 의도를 우선한다.
  크래시 후에는 journal에 남은 사용자의 저장/폐기 선택을 그대로 재시도한다.
- crash/native crash/ANR/low-memory/signaled/unknown, 조회 실패·이력 없음은 기존 세션 복구 유지.
- 정상 완료 화면에서 저장하는 `recordWorkoutLog`/`FinishedWorkoutStore.finish` 흐름은 변경하지 않았다.
  중도 종료 저장도 기존처럼 완료 세트만 투영하며 현재 미완료 세트를 완료로 만들지 않는다.

## 폐기 범위와 복원 차단

활성 세션 스냅샷(완료·미완료 세트와 진행/휴식/일시정지 상태 포함), 워치 시작 대기 계획,
수동 수정 revision 상태, 세션 운동시간 경계를 지운다. 세션 ID는 기존 closed-session 목록에 남긴다.
폐기 경로는 완료 저장소를 호출하지 않으므로 운동 로그·종목/루틴 지난 기록·신기록·과부하 입력을 만들지 않는다.
이미 따로 저장된 다른 운동 기록이나 루틴 정의는 삭제하지 않는다.
워치 ACK/수신 중복 방지 receipt와 원본 캡처·분석 자료, 종료 진단 파일은 지우지 않는다.
이들은 운동 이력으로 투영되지 않으며 durable STOP은 전송 완료/재연결을 위해 남긴다.

closed-session ID는 오래된 Activity의 `saveActive` 재저장을 차단한다.
늦은 SET_COMPLETE는 기존 closed-session ACK 경로를 따르며 분석 reconcile/replay도 폐기 세션 기록을 만들지 않는다.
기존 `restoreActiveWorkout`의 pending journal 차단을 유지한다.
`MainActivity.onCreate`의 일회성 homeReset은 savedInstanceState의 탭/결과 화면과 결과 Intent 자동 복원을 건너뛴다.
변경 범위는 MainActivity 487–488, 515–525의 초기 복원 처리이며 확인창 함수는 변경하지 않았다.

## 검증 및 한계

종료·복구 대상 검사 19개 통과. 하드 종료의 완료 12회 폐기, 명시적 폐기·저장·빈 저장,
저장 실패 후 재시도, 오래된 writer 차단, 늦은 실제 분석/완료 메시지 후 reconcile/replay,
Activity 홈 복원, 크래시·메모리 부족 복구, 정상 완료 저장을 포함한다.
초기 검사에서 가상 시각 초기화와 분석 manifest fixture 누락을 수정했고 최종 대상 검사 실패는 없다.

전체 검사는 아래 결과에 기록한다. clean/lint/APK 생성·설치·릴리스·push·OTA는 수행하지 않는다.
JDK17/기존 SDK, in-process Kotlin compiler, `/private/tmp/stability-tests.init.gradle`의 테스트 JVM 홈 설정을 사용한다.
각 테스트 task에 `--rerun`을 주어 실제 재실행하며 병렬 Gradle 실행은 하지 않는다.
로그: `work/discard-final-{core,wear,phone,compose}.log`, 집계: `work/discard-final-test-counts.json`.

OS 종료 사유 조회는 lambda 주입 단위 테스트로 검증했다. OEM별 콜백/종료 reason 보고와 판별 정확도는 실기기 미확인이다.
Android 29는 ApplicationExitInfo API가 없어 설정 강제 중지 사유를 확인할 수 없으며 복구를 보존한다.
API 30 이상도 OS 이력이 없거나 사유가 불명확하면 임의로 폐기하지 않는다.
오프라인 워치에는 즉시 STOP 전달을 보장하지 않는다. 새 확인창은 아직 연결 전이며 실제 버튼 클릭/설치 검증은 하지 않았다.

## 변경 경로

- `phone/src/main/kotlin/dev/kyungmin/kcoach/phone/WorkoutTermination.kt`
- `phone/src/main/kotlin/dev/kyungmin/kcoach/phone/WorkoutLaunchRecovery.kt` (신규)
- `phone/src/main/kotlin/dev/kyungmin/kcoach/phone/KCoachApplication.kt`
- `phone/src/main/kotlin/dev/kyungmin/kcoach/phone/PhoneWorkoutService.kt`
- `phone/src/main/kotlin/dev/kyungmin/kcoach/phone/SessionStore.kt`
- `phone/src/main/kotlin/dev/kyungmin/kcoach/phone/WorkoutTimeStore.kt`
- `phone/src/main/kotlin/dev/kyungmin/kcoach/phone/MainActivity.kt` (초기 복원만)
- `phone/src/test/kotlin/dev/kyungmin/kcoach/phone/WorkoutTerminationTest.kt`
- `phone/src/test/kotlin/dev/kyungmin/kcoach/phone/WorkoutLaunchRecoveryTest.kt` (신규)
- `docs/DISCARD_ON_STOP_20261017.md` (이 보고서)

## 전체 테스트 최종 결과

| 작업 | 전체 | 통과 | 실패/오류 | 생략 |
|---|---:|---:|---:|---:|
| core | 312 | 310 | 0 | 2 |
| wear | 94 | 94 | 0 | 0 |
| phone | 246 | 245 | 0 | 1 |
| compose | 81 | 78 | 3 | 0 |
| 합계 | 733 | 727 | 3 | 3 |

core/wear/phone 단위 작업은 BUILD SUCCESSFUL, Compose 작업은 BUILD FAILED다.
생략은 MM-Fit 데이터 미제공 2개와 실측 카메라 캡처 미제공 1개다.

Compose 실패 3개는 `CalibrationResultScreenTest`의 다음 검사다.

- `unalignedWatchLogSaysTheWindowsCannotBeComparedInsteadOfShowingNone`
- `aSuspectLastRepShowsANoteWithoutChangingTheCount`
- `mismatchWindowsAreAnsweredPerWindowAndNeverBecomeLabelsHere`

원인: 기존 테스트는 "워치에서만 찾은 구간", "워치 기록의 시간을 영상에 맞추지 못해…", "워치가 센 마지막 1회는…"를 기대하지만,
현재 화면 소스는 각각 "센서 피크 재분석에서만 찾은 구간", "센서 재분석 구간을 영상 시간에 맞출 수 없어…", "폰 횟수 재분석에서 마지막 후보가…" 문구를 표시한다.
`CalibrationResultScreen.kt` 및 `CalibrationResultScreenTest.kt`는 HEAD와 동일하며 이번 변경에 포함되지 않았다.
이는 종료 정책 테스트 실패가 아니며, UI 파일 금지 범위를 지켜 수정하지 않았다. 메인의 전부 통과 상태는 이 체크아웃에서 확인되지 않았다.
`git diff --check` 통과, `confirmStopWorkout` 함수 본문은 HEAD와 동일함을 문자열 비교로 확인했다.

## 커밋 상태

이 브랜치에만 커밋하려고 명시적 파일 목록으로 `git add`를 실행했으나 실패했다.
실제 git-dir은 `/Users/kmnoh/Documents/Default Project/워치짐/.git/worktrees/워치짐-codex-discard`이며,
현재 샌드박스 쓰기 허용 루트 밖이므로 `index.lock: Operation not permitted`가 발생했다.
staging/커밋은 완료하지 못했고 변경은 워크트리에 남아 있다. push·릴리스·OTA는 실행하지 않았다.
