# 전체 범위 대조 — 2026-10-15

기준: 사용자가 승인한 MEASUREMENT_REDESIGN_PLAN.md, EXERCISE_MEASUREMENT_MATRIX.md,
START_WRIST_ORIENTATION_PLAN.md, AI_COACH_PLAN.md, WORKOUT_RESULT_UX.md와 이후 요청.
최종 완료 선언이 아니다. 대상 테스트를 전체 통합/실물 증거로 대신하지 않는다.
최초 대조 뒤 최종 전체 검사와 통합 업데이트를 진행했다. 이후 폰 0.7.8/워치 0.2.7을 공개했고,
latest manifest와 실제 다운로드 APK SHA-256도 대조했다. 새 reviews JSON은 생성하지 않았다.
소프트웨어 검사/공개와 실제 폰·Watch8·계정 검증의 완료는 구분한다.

후속 [폰 준비 흐름](PHONE_WORKOUT_PREPARATION.md)은 로그인 없는 신체·착용 기본값과 루틴별 준비 질문,
중복 없는 직접 시작/신체 스냅샷을 연결하고 대상 검사한 미배포 소스다. 카메라 가이드 1~3단계는
클로드 34abba2와 추가 b5407ab/376f0b8로 4단계 UI까지 main에 반영됐다. 새 가이드·자동 촬영의 실기,
워치 최종 구성/완료 결과 열기의 기기 간 연결, 식별 가능한 본체 방향 설정은 잔여다.
폰의 요청 결과 진입/복구는 [인계](PHONE_RESULT_NAVIGATION_HANDOFF.md)의 대상 14건으로 확인했다.
워치 호출·실제 기기 전환은 아직 아니다. 모든 구현 후 최종 검사를 진행한다.
늦은 분석이 다른 화면/결과를 교체하던 NAV-001은 실제 Activity 수신 경로의 실패 재현 후 조건을 좁혀
대상 3개 통과로 수정했다. 이는 새 APK나 워치 결과 열기의 완료 증거가 아니다.
0.7.8은 추가 Protobuf Any 필드 보존 수정으로 공개됐으며 실제 관절 초기화 성공은 아직 미확인이다.
아래 0.7.7 검사 수치는 그 공개 시점 이력으로 후속 소스의 전체 검사 결과가 아니다.

## 소프트웨어 경로와 증거 범위

| 요구 | 현재 소스·검사 위치 | 판정과 실제 한계 |
|---|---|---|
| 기본 43종의 동작 그룹·시작/1회/위상·수동 설명 | core MeasurementRecipe/ExerciseTrackingProfiles, MeasurementRecipeTest | 명세/공통 엔진 연결 있음. 설치 폰의 모든 커스텀 종목 조건은 미확인 |
| 손목 관측 불가 7종·추가 미등록 운동 수동 | ManualSetCommand, phone ManualSetIntegrationTest, wear ManualWatchFlowTest/ManualWatchUiTest | 0회/목표와 실제 분리/영속 요청/ACK/중복/충돌 경로 있음. 실기 오프라인 전달은 미확인 |
| 원본 수집·검증 저장·ACK·시도/설정 보존 | WearWorkoutService, CaptureInbox, SetAnalysisStore, AnalysisRecoveryTest | 폰/워치 경로와 품질/복구 대상 검사 있음. 실제 절전·재부팅·센서율/전력 수치는 미확인 |
| 폰 영상/관절/워치 원본·시계 연결 | CalibrationCameraActivity/CalibrationCaptureStore/CaptureClockStore, CaptureClockStoreTest | 연결 코드 있음. 사용자 0.7.5 예외로 Flogger/R8 초기화 실패를 재현하고 keep 규칙을 수정함. 새 APK의 실제 수집 성공은 아직 미확인 |
| 사람 반복 라벨·제외/분할/합치기·출처/revision | CalibrationReviewActivity/CalibrationReviewStore, CalibrationReviewStoreTest | 실제 프레임 구간·완전 반복과 준비/불완전 등 제외 구간을 별도 보존. 내부 정지 UI와 편집 보존도 후속 연결·대상 검사됨 |
| 촬영 저장 실패·영상만 삭제·선택 ZIP | CalibrationCaptureFinalizer/CalibrationVideoRetention/CalibrationEvidenceExport | 같은 시도 재시도/영상·로그 보존/출처 유지/선택 원본 내보내기 대상 검사 있음 |
| 기존/논문 피크/개인 패턴 비교 | PaperPeakRepAnalyzer, ReviewedPatternAnalyzer/Detector, ReviewedPatternValidation | 후보/비교 경로 있음. 실제 Watch8 정답 자료가 없어 최적 후보 선정·정확도 개선은 미완료 |
| 여러 확인 자료 누적·독립/고정 회귀 검증 | ReviewedPatternBuilder, PatternCandidateStore, PatternBenchmarkStore | 소스 조건/원본 중복·학습 재사용 차단·직전 엔진 비교 있음. 실제 다음 날짜 개선은 미확인 |
| 워치 실제 실행·지연 기준과 채택/롤백 | PatternNativeValidation, PatternAdoptionController/PatternTransferOutbox | 실제 센서 trace/동결 설정/피드백 시각을 요구하고 채택 직전 재검증. 실물 통과 후보 없음 |
| 개인 엔진 버전·현재 세트 고정·다음 시도 적용 | WorkoutWearingStore, WearWorkoutService, PatternActiveServiceTest | 경로 있음. 현재 활성화와 다음 세트 적용을 ACK로 구분. 물리 기기 적용은 미확인 |
| 지정 변형 3종만 손목 방향 2초 | START_DIRECTION_IMPLEMENTATION.md의 저장소·전송·서비스 경로 | 33종 보류/7종 수동 유지. 맞는/반대/일상 독립 자료로 검증한 조건만 사용. 실제 방향 분리 자료 없음 |
| 템포·각속도·움직임 변화·가림/단위/미측정 | ObservedMotionMetrics, ReviewedMotionBaseline, CameraObservedAnalyzer/CameraSquatAnalyzer, 결과 패널 | 손목·상체와 추가 전신 영상 관측 소프트웨어 연결. 실제 관측 성능은 미확인 |
| 선택적 워치 템포 알림·연속 조건·빈도·즉시 끄기 | TempoFeedbackPolicy/WorkoutTempoSettings/WorkoutTempoServiceTest | 연결됨. 기본 OFF, 검증 개인 엔진에서만 실행. 실제 진동/체감은 미확인 |
| 즉시 결과·이전 동일 루틴 비교·웜업·수동 우선·늦은 원본 | WorkoutSummaryPanel/WorkoutReviewContext, FinishedWorkoutStore | 연결됨. 기존 결과/요약 UI 대상 검사 있음. 사용자 폰 1초 표시 목표는 미계측 |
| 심박 시계열·결측·추정 칼로리/프로필 | GPT_IMPLEMENTATION_STATUS.md의 구현 경로 | 신선도/간격/입력 조건과 추정 표시 연결됨. 실측 칼로리로 표시하지 않음 |
| 로그인·계정 모델/추론 강도·프로필·앱 지침 | ChatGptAuth/ChatGptModels/SettingsActivity, ai-coach assets | 연결됨. 실제 계정 모델 호출은 미확인 |
| 종료 리뷰·상담 근거 조회·개인 사실/해석 분리 | AiCoachController/ReadTools, AutomaticWorkoutReview, WorkoutReviewController | 연결됨. 코덱스1의 설정 해제/계정 변경/늦은 저장 수정도 8ceff8c로 통합됨 |
| 제한된 GPT 보정 제안·원본 재생·독립 채택 | AiCalibrationController/Input, PatternCandidateStore | 45초/1회 호출/허용 파라미터와 재생 경로 있음. GPT 응답/실물 개선은 미확인 |
| 폰에서 두 기기 OTA 제어·한 기기만 준비돼도 설치 | COMPANION_UPDATE_FLOW.md, updates 모듈/각 ListenerTest | 구현됨. 첫 통합 버전은 구형 워치의 기존 버튼으로 먼저 설치해야 함. 실제 통합 OTA는 미확인 |
| 코덱스1 후순위까지 포함·최종 1회 검사/릴리스 | 8ceff8c/dc0f18f/2ecd3ad, tools/release.sh | 세 후순위 포함 통합 검사·공개 완료. 전체 676건 통과/2건 제외, lint ID 수정 후 대상 1건/폰 lint·OTA 재확인. 실기/계정 전체 목표는 미완료 |

## 확인된 구현 잔여 — 완료를 기기 확인으로만 축소하지 않는다

1. **반복 내부 정지 라벨 UI — 후속 소프트웨어 연결됨.** 정지 시작/끝·수정/삭제·반복 분할/합치기 보존과
   실제 프레임 대응을 연결하고 대상 검사했다. 미표시 정지는 미확인이며 정지 반복은 현재 DTW의 양성 학습에서
   제외한다. work/reviewed-pause-focused.log. 실물 관측 검증은 아직 별도다.
2. **추가 G12 스쿼트 카메라 계약 — 후속 소프트웨어 연결됨.** [G12 구현](G12_CAMERA_IMPLEMENTATION.md)은
   5개 촬영 진입 항목/공통 레시피·선 자세/완전 왕복 후보·상대 관절각·골반 영상 이동·영속 저장/ZIP을 연결했다.
   워치 수동 정책과 기존 43종은 유지한다. 대상 검사 통과이며 사용자 폰 초기화/실제 전신 관측은 미확인이다.
3. **사용자 폰 관절 엔진 초기화 실패 — 원인 재현·소스 수정됨.** 사용자가 0.7.5의
   ExceptionInInitializerError → IllegalStateException(`no caller found on the stack for: S2.c`)를 제공했다.
   실제 Flogger 0.6 초기화를 R8 classfile 최적화하면 같은 예외로 실패하고, 폰의 Flogger keep 규칙을
   추가하면 통과한다. 기존 0.7.5 APK의 JNI 4개는 있지만 필수 Flogger 메서드 보존은 실패했다.
   tools/verify_flogger_r8.py는 이 경로만 검사하며 MediaPipe native/사용자 폰 몸 인식 성공은 별도다.
4. 최종 전체 실행은 core 265(2건 제외)/updates 14/phone 322/wear 77로 실패 없이 끝났다.
   폰 lint의 View ID 3곳 오류로 공개 전에 중단했으며 정식 리소스 ID로 바꿨다.
   이후 해당 대상 테스트 1건/폰 lint·OTA만 다시 실행해 통과했고 다른 모듈 전체 테스트는 반복하지 않았다.
   tools/release.sh --publish-built로 이미 검사한 APK의 공개를 이어갔다.
   새 축소 폰 APK는 필수 JNI 4개/Flogger 스택 메서드 2개를 모두 보존한다.
   두 APK 서명은 기존 설치본의 인증서 SHA-256과 일치한다. 사용자 폰 native/몸 인식은 아직 미확인이다.
   공개됐다 초안으로 돌린 phone code 36/watch code 34는 재사용하지 않는다.
   [통합 업데이트](https://github.com/kmnoh12/kcoach/releases/tag/p0.7.7-w0.2.7)는
   phone 0.7.7(code 37)/watch 0.2.7(code 35), 소스 2ecd3ad다. 최신 공개 상태/양쪽 manifest/실제 APK 해시를 확인했다.
   기존 reviews/2026-10-14-start-wrist-context.json은 그때 작성된 미커밋 기록을 재생성 없이 보존했다.
   기록된 옛 APK SHA 두 개는 현재 남은 옛 OTA 파일과 일치하며 artifactScope도 그 당시 조건 전달에 한정돼 있다.
   이 기록을 새 통합 검사나 관절 초기화 성공 증거로 사용하지 않는다.

## 실측/계정 확인의 완료 조건

원래 승격 목표(20세트·3일·150회, 90% 정확 일치/95% ±1/MAE≤0.3, 경계/피드백 지연)를
낮추거나 fixture 통과를 실측으로 승격하지 않는다. 실제 Watch8 원본/영상/사람 확인 자료와
실제 로그인 계정 응답이 필요하다. 사용자 승인 업데이트→사용 후 문제 보고 흐름에 따라 진행하되,
최종 전체 목표를 실물 정확도 확인 없이 완료 처리하지 않는다.
