# 코덱스1 병행 작업 인계 — 2026-10-15

`CODEX1_PARALLEL_TASKS.md`의 세 작업을 별도 worktree에서 완료했다.
기준 커밋은 `40b2cf0`, 브랜치는 `codex/parallel-followups`다.
메인 작업과 병합하지 않았으며 최종 전체 검사·OTA·버전 상승·공개 배포는 코덱스2가 맡는다.

## 커밋과 변경 파일

- `5a542e0`: `core/.../TempoFeedbackPolicy.kt`, `core/.../TempoFeedbackPolicyTest.kt` 신설.
- `1de7087`: `tools/inspect_measurement_export.py`, `tools/test_inspect_measurement_export.py`.
- `bc8c510`: 폰의 `AiCoachContext.kt`, `AiCoachController.kt`, `AutomaticWorkoutReview.kt`,
  `WorkoutReviewController.kt`, `AiCoachControllerTest.kt`, `WorkoutReviewTest.kt`.

작업 중의 코덱스2 파일을 이동·덮어쓰기·커밋하지 않았다.
완료 시점 메인의 커밋 및 미커밋 변경과 수정 경로가 겹치지 않았다.
경로 대조 결과이며, 최종 병합과 통합 동작 검사를 대신하는 증거는 아니다.

## 템포 판정기 연결 계약

`TempoFeedbackPolicy` 인스턴스를 운동 세션마다 하나 만들고 순서대로 호출한다.
`Config`는 기본 OFF이고, 사용자가 정한 `targets`와 `consecutiveRepetitions`,
`minimumAlertIntervalMs`를 받는다. 목표는 REPETITION / CONCENTRIC / ECCENTRIC별 ms 범위다.

확인된 완전 반복에 대해 `observe(Observation(...))`를 호출한다.
`setId`, `attemptId`, 반복 순번, 같은 단조 시계의 `endNs`, 실제 측정 시간을 전달한다.
`qualitySufficient`는 기본 false이고 호출자가 측정 품질 판정을 전달해야 한다.
수축·이완 시간은 `phasesValidated=true`이고 실제 관측 시간이 있을 때만 사용한다.
부분 반복·누락 위상·품질 부족에서 시간을 추정하지 않는다.

워치가 일시정지할 때 `pause()`를 호출한다. 목표/활성화 변경에는 `configure()`를 호출한다.
세트·시도 전환, 반복 순번 누락, 정상 범위 복귀, 이탈 방향 전환은 연속 조건을 끊는다.
중복·역행 이벤트와 이미 종료한 시도의 늦은 이벤트는 새 알림을 만들지 않는다.
알림 간격 제한은 일시정지·시도 전환에도 유지된다.

반환값이 null이 아니면 관측 시간·목표·이탈 방향·연속 횟수 근거를 가진 피드백이다.
워치 서비스·진동·UI·설정 및 동기화 연결은 미구현이며 코덱스2가 담당한다.
현재 측정의 성능 개선, 자세 정확도, 피로, RIR을 뜻하지 않는다.

## ZIP 보고 계약

기존 파일·내보내기 형식·SHA·시도 식별·사용자 확인 0회 처리를 보존했다.
추가 `sensor` 필드:

- `accelerationJoin`: 원본 gyro−accel 시각의 최소/최대 결합 나이, 관측 수,
  40ms 초과 stale 수, 미래 가속도 수, 가속도/자이로/콜백 원본 시각 누락 수.
  40ms 경계는 현재 메인의 `SensorRecordQuality`와 같다.
- `gyroscopeAccuracy`, `accelerationAccuracy`: unknown(-1), unreliable(0), low(1),
  medium(2), high(3), 정의되지 않은 정확도 값의 수를 각각 보고한다.
- `eligibleForSensorMetadataReview`, `sensorMetadataIssues`: 센서 메타데이터 검토에
  필요한 근거의 충족 여부와 빠지거나 불량한 근거. 엔진·실기기 승인이 아니다.

KCS v1에는 가속도 정확도와 원본 결합 시각이 없어 unknown 및 null로 남는다.
품질 부족이어도 정상적으로 보존된 ZIP의 무결성은 별도로 보고하며,
음수·비정상 원본 시각, 비단조 센서 시각, 길이 손상은 기존 무결성 거절 경로를 따른다.

## AI 재현 결함과 수정

가짜 transport를 실제 auth/controller/store에 연결해 다음 다섯 저장·소유권 결함을 먼저 재현했다.

1. 상담 OFF 뒤 늦은 완료 응답이 저장됨.
2. 리뷰 OFF 뒤 늦은 완료 응답이 저장됨.
3. 자동 리뷰만 OFF하고 수동 리뷰는 ON인 경우 자동 요청의 늦은 응답이 저장됨.
4. 상담 해석의 중복 근거 ID가 해석 저장에서 거절되기 전에 대화가 먼저 저장됨.
5. 다른 요청 때문에 대기한 종료 이벤트가 이후 선택한 계정에 새 기록을 연결함.

요청 동안 설정 해제를 관찰해 취소하고, 준비/저장 직전에도 활성화·계정을 확인한다.
중복 해석 참조는 대화 저장 전의 응답 파싱에서 거절한다.
자동 종료 이벤트는 수신한 계정·연결 세대를 고정하고, 대기 중 변경되면 포기한다.
초기 auth 로딩 중에는 처음 로딩된 계정·연결 세대를 고정한다.

기존 호출 형태 `AutomaticWorkoutReview.finished(context, sessionId)` 및 `reviews.start()`는 유지된다.
auth/controller 주입 인수는 테스트를 위한 기본값 있는 인수다.
자동 큐에서만 `start(automatic=true)`를 써 자동 리뷰 OFF와 수동 리뷰 OFF를 구분한다.
설정 화면이나 다른 공통 진입 파일은 수정하지 않았다.

중복 종료와 controller 재생성 뒤 자동 실패 요청을 재발행하지 않는 것,
계정 재바인딩/로그아웃 표시/취소/시간 초과 뒤 응답 미저장,
수동 원본·프로필·센서 revision 변경 시 응답 거절 및 계산 요약 갱신,
해석 근거 변경 시 현재 해석에서 제거하는 것을 확인했다.
상담 읽기 도구는 실제 controller를 통해 2회 조회/6호출/최종 답변 제한,
암호화 reasoning 보존, 다른 계정 자료 거절, 중복 call ID·다른 namespace·과다 호출 거절,
라운드 사이 취소 시 후속 모델 호출 중단을 확인했다. 실제 계정 API 호출은 하지 않았다.

## 대상 검사 결과

- `:core:test --tests '*TempoFeedbackPolicyTest'`: 7건 통과.
- `python3 -m unittest discover -s tools -p test_inspect_measurement_export.py -v`: 10건 통과.
- `:phone:testDebugUnitTest --tests '*AiCoachControllerTest' --tests '*AiCoachContextTest' --tests '*WorkoutReviewTest'`:
  14 + 5 + 12 = 31건 통과.
- `git diff --check`: 통과.

합계 48건은 대상 단위·합성 검사다. 실제 Watch8 정확도나 실제 ChatGPT 계정 사용 증거가 아니다.
Python 검사는 기존 Kotlin 내보내기 fixture(`work/selected-capture-evidence-fixture.zip`)와
그 ZIP 안에 넣은 짧은 합성 KCS를 사용했다. 이 worktree에는 메인 fixture를 가리키는
ignored symlink만 두었다. 원본 fixture를 수정하거나 복사하지 않았다.

중간 전체 모듈 검사·lint·OTA·reviews JSON은 실행/생성하지 않았다.
코덱스2는 자기 작업을 마친 뒤 이 세 커밋을 통합하고, 최종 전체 검사와 릴리스를 진행한다.
실기기 입력, 실제 계정 통신, 워치 템포 연결 및 최종 통합 상태는 미확인이다.
폐기된 공개 0.7.6/0.2.6 코드(36/34)를 최종 배포에 재사용하지 않는다.
