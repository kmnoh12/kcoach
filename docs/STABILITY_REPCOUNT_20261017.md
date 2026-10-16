# 반복 끝처리·종료·로컬 진단 작업 보고

작업: `codex1/stability-repcount` / `워치짐-codex-stability`. 실기기·adb·실기기 종료 로그 없음.
UI 화면/설정/BackHandler/onBackPressed/뒤로가기 확인 팝업/워치 표시 파일과
`LoggedSet`·`ExerciseHistory`·`FinishedWorkoutStore`는 수정하지 않았다.
릴리스, OTA, 버전, 서명, push 없음. 아래 결과는 소스·단위/Robolectric 근거이며 실기기 미확인이다.

## 1. 마지막 반복 뒤 추가 카운트와 자동 완료

### 확인한 원인

- `CapturePurpose.WORKOUT.stopAtTarget=false`. 기존 일반 운동은 목표 도달 진동만 주고 센서를 계속 받는다.
  `recordDetectedRep()`에는 세트 종료 타임아웃이 없었다. 목표 10회를 10회에서 잘라내는 대신 12회까지 받는 설계이지만,
  버튼을 누르기 전까지 검출기가 다른 손목 움직임을 새 반복으로 받아들일 수 있는 구간도 계속 열려 있었다.
- `TrailingRepSuspicion`은 보정 결과 화면에서 느리고 약한 마지막 반복에 주의를 표시한다.
  일반 운동 서비스의 종료/횟수 확정 장치가 아니다. 횟수를 조용히 빼는 방식으로 변경하지 않았다.
- `ProfiledMotionRepDetector`의 signed lobe 두 개, `ReturnToPoseRepDetector`의 출발·복귀,
  `ForearmRotationRepDetector`의 축 학습·회전 왕복은 손목 신호 조건이다. 같은 운동이라는 완전한 증명은 아니다.
  `UpperBodyPoseCounter`는 카메라 측정 경로로, 일반 워치 세트의 소유자가 아니다.
- `WorkoutSessionReducer`는 SET_COMPLETE 수신 뒤 실제 reps를 저장하고 RESTING으로 옮긴다.
  `RestCountdownCalculator`는 주어진 절대 종료시각의 표시값만 계산한다. 두 클래스가 자동 완료를 발생시키지는 않는다.
- 카메라 보정의 `CameraCaptureEnd`(목표 이후 시작 자세 유지)와 옛 calibration의 목표 즉시 완료는 별도 경로다.
  일반 워치 운동에는 이와 동등한 끝처리가 없었다. 사용자 베타 불안정 보고 전체가 동일 원인인지는 미확정이다.

### 변경

- `SetEndGate`를 일반 운동의 Wear 서비스에 연결했다. 첫 실제 반복 이후 다음 검출이 없으면 기본 3초에 자동 SET_COMPLETE.
  목표는 상한으로 쓰지 않으며, 연속 반복이 들어오면 매번 종료 시각을 갱신한다. 목표 전 중단도 자동 완료된다.
- 100ms 서비스 타이머와 센서 입력 직전 검사로 종료 경계 뒤 입력을 차단한다. 닫힌 gate는 추가 rep를 받지 않는다.
  기존 SET_COMPLETE 재전송/폰 durable 수신/REST 동기화 경로를 그대로 사용한다.
- qualified 진행 중 반복은 deadline 전 시작했을 때만 복귀를 기다린다. 회전/일반 lobe는 기존 최소 변위 조건을 통과해야 한다.
  자세 복귀는 시작 각도에서 벗어났는지를 사용하며, 복귀 전에는 카운트하지 않는다.
- 느린 복귀와 긴 top hold를 기존 pose 검출기는 허용했지만 프로필 표면의 maximumRepMs는 4.5초였다.
  이 상한을 종료에 바로 쓰면 6.5초짜리 반복이 1회 이후 잘리는 것이 합성 회귀에서 재현됐다.
  pose 경로의 진행 중 반복은 최대 12초로 제한해 기다린다. 무한정 미완료 자세로 세트를 붙잡지는 않는다.
- 개인 학습 패턴은 partial-cycle 시작을 제공하지 않는다. 고정된 학습 프로필의 최대 반복 시간+300ms(최소 3초)를 기다리는 보수적 예외를 둔다.
  300ms는 detector의 100ms 탐색 간격과 120ms 확정 지연을 포함한 여유다. 프로필 상한을 넘는 감속은 보호하지 못한다.
  이어하기에서 기존 템포 회귀가 실제 실패했다. 직전 반복 길이+300ms는 3초 동작+반복 사이 정지+검출 지연을
  감당하지 못해 두 번째 반복 전 reps=1에서 닫혔다. 고정 프로필 상한으로 바꿔 이 회귀를 수정했다.
  준비 자세 gate 통과 시에도 이 값을 3초로 되돌리지 않도록 유지한다. 같은 템포 회귀가 준비 완료 경로도 거친다.
- 자동 종료 때 옛 detector.finish()로 마지막 횟수를 새로 만들지 않는다. 수동 종료의 역사적 flush는 유지했다.
  보정/paired-reference에는 새 일반 운동 정책을 적용하지 않는다.

### 검증과 한계

합성 검출기 재생: 깔끔한 10회→3초 이상 정지→추가 손목 왕복은 10회, 연속 12회는 12회,
1초 상승+3초 하강 및 2.5초 top hold 포함 6.5초 반복도 12회를 보존한다.
Wear 서비스 검사: 실제 타이머 자동 완료, 완료 후 rep 차단, 10회 목표의 연속 12회 SET_COMPLETE,
수동 종목 자동 종료 금지를 검사한다.

**완전히 해결했다고 주장하지 않는 부분:** 3초 안에 무관한 움직임이 검출기의 유효 왕복 조건까지 충족하면
그 움직임을 운동으로 오인할 수 있다. 후보가 시작된 채 hold하면 기본 3초보다 오래 기다릴 수 있다.
IMU만으로 운동 의미를 완전히 구별할 수 없으므로 실기기 라벨 캡처가 필요하다.
학습 프로필 최대 시간을 넘는 갑작스러운 감속, 센서 전달 지연, 프로세스 복구 직후(종료 gate 자체는 메모리 상태),
pause 후 첫 새 반복 전의 자동 종료는 추가 검증이 필요하다.
3초는 사용자 요청에서 온 정책값이며 연구·Watch8 캡처로 최적화된 임계값이 아니다.
12초와 학습 패턴의 +300ms 역시 provisional 안전 여유이며 실측 검증값이 아니다.
이 워크트리에는 기존 `work/` 폴더나 실측 `.kcs` 캡처가 없었고, 이번 `work/`는 새 테스트 로그다.

## 2. 초과 횟수 저장/색상

`recordDetectedRep`는 목표로 clamp하지 않는다. reducer는 `event.reps`를 `performedReps`에 그대로 넣고,
완료 기록 투영도 완료 세트의 performedReps를 쓴다. 12회 완료 세트를 종료·보존하는 검사도 추가했다.
늦은 폰 분석이 WATCH 추정치를 PHONE_ANALYSIS로 교체하는 기존 동작은 유지한다(수동 확정값은 보존).
따라서 12회가 영구히 무조건 바뀌지 않는다는 뜻은 아니다. 분석 재평가도 동일 원본을 기준으로 이뤄진다.

색상은 `wear/.../MainActivity.kt:362`에서 `count > targetReps`일 때 `WatchWorkoutFace.ORANGE`다.
현재 빨강이 아니다. Claude 담당으로 남기고 해당 파일을 수정하지 않았다.

## 3. 예고 없는 종료·내부 상태 변화

실제 이번 종료의 확정 원인은 없다. 우선 조사 후보와 근거 수준은 다음과 같다.

1. **메인 스레드 미처리 예외 — 코드 위험, 이번 사고와의 상관 미확인.** 명령 enqueue/bind의 getOrThrow와
   화면 전환·기록 읽기가 호출자에게 예외를 전달할 수 있다. 종료 명령 경로는 Result로 감쌌다.
   이 변경이 사용자가 경험한 크래시의 직접 원인을 고쳤다는 뜻은 아니다.
2. **메모리 압박/네이티브 종료 — 코드상 후보.** 카메라는 프레임 Bitmap/회전 Bitmap/MediaPipe/비디오 인코더를 함께 사용하고,
   캡처 수신은 최대 32MiB 원본을 ByteArrayOutputStream→ByteArray→파싱 객체로 만든다.
   상한과 예외 처리는 있으나 실제 peak RSS나 native crash·OOM 로그는 없다.
3. **메인 스레드 I/O/ANR — 코드상 후보.** 세션 경계의 SharedPreferences.commit, 완료 기록 투영 등이 동기 작업이다.
   기록 복구를 worker로 옮긴 기존 개선이 문서에 있으며 이것을 현재 ANR의 증거로 사용하지 않았다.
4. **화면 재생성/늦은 분석·복원 — 이력 확인, 현재 사고는 미확인.** BUG_REPORT의 늦은 분석 탭 교체는 이미 좁혀진 코드이고,
   메모리의 Activity 상태와 durable snapshot을 혼동하면 내부 정보가 바뀐 것처럼 보일 수 있다.
   과거 CAM JNI/Flogger/Protobuf 초기화 오류 이력도 이번 일반 운동 종료 원인이라고 단정할 수 없다.

`LocalExitDiagnostics`를 Application 시작에 설치했다.
앱 내부 `noBackupFilesDir/exit-diagnostics/`에 아래 세 파일만 덮어써 크기를 제한한다.

- `last-exception.json`: 최근 Java 미처리 예외의 최대 6개 cause, 각 48개 코드 stack frame, 화면·운동 phase·시각.
- `last-state.json`: 마지막 화면 종류/WORKING·RESTING·PAUSED·IDLE 등 상태·시각.
- `system-exits.json`: API 30+의 최근 ApplicationExitInfo 8개(reason/status/importance/PSS/RSS/시각)와 이전 실행의 마지막 상태.

운동 이름·중량·반복·세션 ID·신체 정보·Intent extras·예외 message·OS description/trace는 넣지 않는다.
네트워크 전송 및 UI 노출·공유 기능을 추가하지 않았다.
읽기 API `LocalExitDiagnostics.readReports(): Result<Map<String, String>>`를 제공한다.
고정된 세 파일의 이름과 JSON 문자열만 반환하며, 미생성 파일은 생략하고 읽기 오류는 Result.failure로 돌려준다.
UI 담당은 worker에서 이 API를 호출해 표시할 수 있다. 현재 일반 사용자가 앱 화면에서 열어보는 기능은 없다.
실제 파일 위치는 `/data/user/0/dev.kyungmin.kcoach/no_backup/exit-diagnostics/`
(정확한 루트는 `Application.noBackupFilesDir`)다. Android 내부 전용 저장소라 일반 파일 앱으로는 접근되지 않는다.
debuggable 설치와 adb가 있을 때 예: `adb shell run-as dev.kyungmin.kcoach cat no_backup/exit-diagnostics/last-exception.json`.
이 추출 명령은 실기기에서 실행하지 않았다. noBackup 위치라 Android 자동 백업에서도 제외된다.
기존 기본 예외 처리기에 위임하며 크래시를 정상 성공으로 숨기지 않는다.
Native crash/ANR/메모리 kill은 Java handler가 못 잡을 수 있어 다음 실행에서 OS 종료 이력을 보완한다.
ApplicationExitInfo의 실기 OS 이벤트 수집과 추출/열람 UX는 실기기 미확인이다.

## 4. 최근 앱에서 닫기

기존 PhoneWorkoutService는 활성 운동에 START_STICKY를 반환하고 onTaskRemoved를 구현하지 않았다.
새 경로는 사용자 task 제거에만 종료 journal→STOP durable enqueue→완료 세트 보존→active clear를 적용한다.
그 뒤 heartbeat/rest 콜백·알림·서비스를 멈춘다. 종료 journal이 남으면 앱 시작에서 저장을 재시도하며
완료 전에도 운동 화면으로 복원하지 않는다. 일반 Service.onDestroy는 active snapshot을 지우지 않는다.

`stopWithTask=false`를 명시했다. true이면 Android가 서비스를 바로 멈추고 onTaskRemoved를 부르지 않으므로,
보존·워치 STOP 처리를 먼저 하기 위해 false+명시적 stopSelf를 선택했다.
폰 bridge는 종료 시 이미 큐에 넣은 STOP을 바로 버리지 않고 전송 시도가 끝난 뒤 닫는다.
전송이 실패하면 outbox에 남아 재연결 때 재시도한다. **워치가 오프라인이면 즉시 종료를 보장할 수 없다.**
현재 워치 heartbeat timeout은 paired만 해제하고 운동을 보존한다. 이를 일괄 STOP으로 바꾸면
단순 Bluetooth 단절/폰 low-memory kill도 사용자의 명시 종료로 오인하므로 변경하지 않았다.

공식 계약상 onTaskRemoved는 사용자 task 제거 콜백이며 low-memory 종료와 다르다.
OEM이 콜백을 생략하거나 OS 강제 중지처럼 코드를 실행할 기회가 없는 경우는 앱이 즉시 정리할 수 없다.
ApplicationExitInfo.REASON_USER_REQUESTED 자체는 최근 앱 제거와 설정의 강제 중지를 구분하지 못하고,
일부 OS의 메모리 kill은 LOW_MEMORY 대신 SIGNALED로 보고될 수 있다. 종료 이력을 자동 삭제/판단 신호로 쓰지 않았다.

**저장 기본값:** 이미 완료한 세트만 기록으로 남기고 미완료 현재 세트는 완료로 만들지 않는다.
기록이 0세트면 FinishedWorkoutStore가 빈 운동을 만들지 않는다. 완료 기록이 안전하게 저장된 후에만 active를 지운다.
저장 실패는 journal+원본 snapshot을 유지하며 재시도한다.
사용자에게 확인할 정책: “닫을 때 완료 세트 보존”을 계속 기본으로 할지, 명시 중단 버튼에서도 같은 정책을 쓸지.
현재 구현은 두 명시 종료에 동일한 보존 경로를 쓴다. 기존 종료 팝업의 “기록은 저장되지 않아요” 문구는
Claude가 새 정책에 맞춰 바꿔야 한다. 팝업 문구/구조/뒤로가기 연결은 건드리지 않았다.

## 5. 운동 강제종료 버튼

MainActivity의 기존 연결은 onStop→confirmStopWorkout→STOP 전송→active clear→showHome이다.
finish/finishAffinity/killProcess/exitProcess로 갤럭시 홈에 보내는 코드는 이 경로에서 확인하지 못했다.
즉 “원래 앱을 죽이도록 설계돼 있다”는 근거는 없다. 사용자 실기 강종은 재현되지 않았다.
종료 backend의 저장/명령 예외가 콜백 밖으로 새지 않도록 Result/journal 경로로 바꿨다.
정상·중복 stop, 손상된 완료 저장소를 이용한 실패 주입, snapshot 보존/복구, Activity.isFinishing=false를 검사한다.
이 검사는 backend 경계 검사이며 실제 버튼 클릭부터 Galaxy launcher 전환까지 재현한 검사가 아니다.
따라서 원래 강종 증상은 **원인 미확정/수정 효과 실기기 미확인**으로 남긴다.

## MainActivity 변경 위치

- 458–462: currentScreen setter에서 화면 enum과 운동 phase만 로컬 진단에 기록.
- 2730–2733: 기존 확인 후 실행 backend를 Result 기반 종료·보존과 durable STOP retry로 교체.
- 3019: 미완료 종료 journal이 있는 세션을 다시 운동 화면으로 복원하지 않음.

UI 파일, 확인 팝업 표현, BackHandler/onBackPressed는 수정하지 않았다.

## 공식 API 근거

- https://developer.android.com/reference/android/app/Service#onTaskRemoved(android.content.Intent)
- https://developer.android.com/reference/android/app/ApplicationExitInfo

## 테스트 결과

사용자의 이어하기 지시에 따라 core/wear/phone의 전체 단위 테스트를 직렬 실행한다.
phone의 별도 Compose/스크린샷 작업(`testDebugCompose`)은 UI 제외 범위이므로 실행하지 않는다.
lint/clean build/APK 생성·설치·실기 정확도 검증은 하지 않는다.
실행별 로그는 `work/`, 최종 모듈별·테스트별 집계는 `work/stability-final-test-counts.json`.
초기 Kotlin daemon/잠금 파일 경로 권한 오류는 in-process compiler와 임시 테스트 홈으로 해결했다.
이전 `ForearmRotationRepDetectorTest` 2건의 NoClassDefFoundError는 재검사한다.
동시 빌드 충돌은 후보일 뿐, 당시 원본 예외 스택과 프로세스 정보가 없어 원인으로 단정하지 않는다.


### 검증 환경과 범위

- JDK: `/Users/kmnoh/.local/share/android-tooling/jdk17/Contents/Home`
- SDK: `/Users/kmnoh/.local/share/android-tooling/android-sdk`
- Gradle: `/Users/kmnoh/.local/share/android-tooling/gradle-8.11.1/bin/gradle`
- `--no-daemon --project-cache-dir /private/tmp/kcoach-stability-gradle-final`
  `-Pkotlin.compiler.execution.strategy=in-process -I /private/tmp/stability-tests.init.gradle`.
- init script는 Test JVM의 `user.home=/private/tmp/kcoach-stability-test-home`,
  `maven.repo.local=/Users/kmnoh/.m2/repository`만 설정한다. 앱/검출기 동작을 대체하지 않는다.
- 각 모듈 test task에 `--rerun`을 사용해 테스트 자체를 실제 실행했다. clean/full build/서명 작업이 아니다.
- `:core:test`, `:wear:testDebugUnitTest`, `:phone:testDebugUnitTest`를 한 번에 하나씩 실행했다.
  phone의 원래 Gradle 설정이 분리한 `testDebugCompose`(ui 패키지 및 CalibrationCameraActivityTest)는 제외한다.
- 상세 집계는 XML의 tests/failures/errors/skipped를 합산하고 통과=전체-실패-오류-생략으로 계산한다.
- 소스/테스트/컴파일 근거까지만 확보한다. APK 생성 이후의 설치·실기 런타임·센서 정확도 단계는 미확인이다.


### 최종 실행 결과 (2026-10-17, 현재 트리)

| 모듈 | 전체 | 통과 | 실패/오류 | 생략 | Gradle |
|---|---:|---:|---:|---:|---|
| core | 312 | 310 | 0 | 2 | BUILD SUCCESSFUL |
| wear | 94 | 94 | 0 | 0 | BUILD SUCCESSFUL |
| phone (별도 Compose 제외) | 232 | 231 | 0 | 1 | BUILD SUCCESSFUL |
| 합계 | 638 | 635 | 0 | 3 | 모두 성공 |

LocalExitDiagnostics 단일 검사 1개도 먼저 통과했다. 이 검사는 위 phone 전체 결과에 포함되어 합계에 중복 가산하지 않았다.
각 모듈의 상세 로그는 `work/stability-final-core.log`, `work/stability-final-wear.log`,
`work/stability-final-phone.log`이며 XML은 각 모듈 `build/test-results/`에 있다.

생략된 3개는 다음과 같다. 통과로 계산하지 않았다.

- `CurrentDetectorEvaluationTest.replay current watch detector and phone analyzer on labelled MM-Fit sets()`:
  `KCOACH_MMFIT_DIR` 원본 데이터 미설정.
- `PeakEngineComparisonTest.compareFrozenPeakCandidateWithCurrentDetectorOnIdenticalOriginalSamples()`:
  변환된 MM-Fit 원본 데이터 디렉터리 미제공.
- `CameraEndFieldReplayTest.replayObservedFramesAndReportWhetherTheyContainAnEndHold`:
  별도 실측 카메라 캡처 미제공.

최종 실패 테스트 이름: 없음.
이어하기 첫 wear 실행에서는 8개 중 7개 통과/1개 실패였으며,
실패명은 `WorkoutTempoServiceTest.liveValidatedCyclesAlertOnlyAfterTheStreakAndTheUserCanMuteThisAttempt`다.
`work/stability-wear-resumed.log` 및 `work/stability-tempo-diagnosis.log`에 실패 근거를 보존했다.
프로필 상한 대기 수정 후 단일 클래스 3개와 전체 wear 94개가 모두 통과했다.
수정한 테스트는 준비 완료 이후 연속 카운트/템포 경고/음소거와 이후 정지 자동 완료까지 확인한다.

이전 `ForearmRotationRepDetectorTest`의 NoClassDefFoundError 2건은 이번 전체 core 실행에서 재현되지 않았고,
해당 클래스 9개 전부 통과했다. 검출기 추가 변경 없이 통과했으므로 현재 소스의 재현 가능한 검출 로직 실패라는
증거는 없다. 당시 원본 stack 및 동시 프로세스 기록이 남아 있지 않아 동시 빌드 충돌을 확정할 수는 없다.
캐시/산출물 교체 등 환경성 일시 실패와 부합하지만 **정확한 이전 원인은 미확정**이다.

### 문제별 회귀 근거와 미확인 범위

| 문제 | 이번 최종 실행에서 통과한 관련 검사 | 미확인 |
|---|---|---|
| 1 추가 카운트/자동 완료 | SetEndGateTest 7, SetEndServiceTest 3, WorkoutTempoServiceTest 3, CurlCycleRegressionTest 11, ProfiledMotionRepDetectorTest 8, ForearmRotationRepDetectorTest 9 | Watch8 실제 동작 정확도, 3초 내 무관한 유효 왕복, 전달 지연/복구/pause 경계 |
| 2 초과 횟수 저장 | SetEndServiceTest 3에 12회 SET_COMPLETE 포함, WorkoutSessionReducerTest 3, PerformedRepsTest 15, WorkoutTerminationTest 5에 12회 보존 포함 | 실제 워치 색상과 폰 기록까지의 실기 연동. 소스 색상은 주황이며 빨강으로 고치지 않음 |
| 3 예고 없는 종료 | LocalExitDiagnosticsTest 1: 메시지 개인정보 제외, 코드 프레임/상태 기록, 읽기 API 원문 반환/원본 유지 | 실제 간헐 종료 원인, OS 종료 이력 실기 수집, 사용자 열람 화면 |
| 4 최근 앱 닫기 | WorkoutTerminationTest 5, BackgroundWorkoutTest 13, IdleServiceTest 5 | OEM별 task 제거 콜백, low-memory/force-stop 실기 차이, 오프라인 워치 즉시 종료 |
| 5 강제종료 버튼 | WorkoutTerminationTest 5에 정상/중복 종료·예외 주입·복구·Activity.isFinishing=false 포함 | 실제 버튼 클릭→Galaxy launcher 이동 재현은 하지 못함. 원래 강종 원인 및 실기 수정 효과 미확정 |

위 테스트는 여러 문제를 함께 다루므로 행별 숫자를 더해 전체 건수로 사용하지 않는다.

### UI 담당에게 전달할 결정

완료 세트 보존을 기본으로 구현했고, 최근 앱 닫기와 명시적 운동 종료 버튼에 같은 경로를 적용했다.
사용자에게 확인할 것은 이 보존 기본값을 유지할지, 명시적 종료만 기록 폐기로 바꿀지다.
현재는 지시서의 데이터 손실 방지 기본값을 따르므로 답을 기다리며 작업을 멈추지 않는다.
기존 팝업의 “지금까지의 기록은 저장되지 않아요.”는 현재 동작과 다르다. UI 담당이 문구를 맞춰야 한다.
워치 초과 횟수 색상도 현재 ORANGE다. 요청한 빨강 적용은 UI 담당에게 남겼다.

`MainActivity` 변경 줄은 458–462(진단 상태), 2730–2733(종료 backend), 3019(종료 journal 복원 차단)다.
UI/뒤로가기 연결/워치 표시/다른 담당 저장소와 엔진의 파일 변경은 없고, `git diff --check`도 통과했다.
커밋·push·릴리스·OTA·버전 변경·서명은 하지 않았다. 변경은 이 워크트리에 남겨 두었다.
