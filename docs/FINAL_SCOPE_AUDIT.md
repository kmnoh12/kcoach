# 전체 범위 대조 — 2026-10-15

기준: 사용자가 승인한 MEASUREMENT_REDESIGN_PLAN.md, EXERCISE_MEASUREMENT_MATRIX.md,
START_WRIST_ORIENTATION_PLAN.md, AI_COACH_PLAN.md, WORKOUT_RESULT_UX.md와 이후 요청.
최종 완료 선언이 아니다. 대상 테스트를 전체 통합/실물 증거로 대신하지 않는다.
이번 대조에서 전체 테스트·lint·OTA·reviews JSON 생성·배포를 실행하지 않았다.

## 소프트웨어 경로와 증거 범위

| 요구 | 현재 소스·검사 위치 | 판정과 실제 한계 |
|---|---|---|
| 기본 43종의 동작 그룹·시작/1회/위상·수동 설명 | core MeasurementRecipe/ExerciseTrackingProfiles, MeasurementRecipeTest | 명세/공통 엔진 연결 있음. 설치 폰의 모든 커스텀 종목 조건은 미확인 |
| 손목 관측 불가 7종·추가 미등록 운동 수동 | ManualSetCommand, phone ManualSetIntegrationTest, wear ManualWatchFlowTest/ManualWatchUiTest | 0회/목표와 실제 분리/영속 요청/ACK/중복/충돌 경로 있음. 실기 오프라인 전달은 미확인 |
| 원본 수집·검증 저장·ACK·시도/설정 보존 | WearWorkoutService, CaptureInbox, SetAnalysisStore, AnalysisRecoveryTest | 폰/워치 경로와 품질/복구 대상 검사 있음. 실제 절전·재부팅·센서율/전력 수치는 미확인 |
| 폰 영상/관절/워치 원본·시계 연결 | CalibrationCameraActivity/CalibrationCaptureStore/CaptureClockStore, CaptureClockStoreTest | 연결 코드 있음. 사용자 폰 0.7.5의 POSE_INIT 실패가 해결되지 않아 실제 수집 성공을 주장할 수 없음 |
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
| 코덱스1 후순위까지 포함·최종 1회 검사/릴리스 | 8ceff8c/dc0f18f, tools/release.sh | 세 후순위 병합/템포 연결 완료. 아래 구현 완료 뒤 버전/서명/manifest/최종 전체 검사·공개 필요 |

## 확인된 구현 잔여 — 완료를 기기 확인으로만 축소하지 않는다

1. **반복 내부 정지 라벨 UI — 후속 소프트웨어 연결됨.** 정지 시작/끝·수정/삭제·반복 분할/합치기 보존과
   실제 프레임 대응을 연결하고 대상 검사했다. 미표시 정지는 미확인이며 정지 반복은 현재 DTW의 양성 학습에서
   제외한다. work/reviewed-pause-focused.log. 실물 관측 검증은 아직 별도다.
2. **추가 G12 스쿼트 카메라 계약 — 후속 소프트웨어 연결됨.** [G12 구현](G12_CAMERA_IMPLEMENTATION.md)은
   5개 촬영 진입 항목/공통 레시피·선 자세/완전 왕복 후보·상대 관절각·골반 영상 이동·영속 저장/ZIP을 연결했다.
   워치 수동 정책과 기존 43종은 유지한다. 대상 검사 통과이며 사용자 폰 초기화/실제 전신 관측은 미확인이다.
3. **사용자 폰 관절 엔진 초기화 실패.** POSE_INIT 내부 오류를 아직 받지 못했다.
   기존 APK의 JNI 선언/라이브러리/모델 존재가 사용자 폰 실행 성공을 증명하지 않는다.
   오류 정보가 없는 동안 근거 없는 SDK/R8/자세 임계값 변경으로 해결 처리하지 않는다.
4. 위 구현 뒤 최종 전체 테스트/lint/OTA를 tools/release.sh의 한 실행에서 진행한다.
   공개됐다 초안으로 돌린 phone code 36/watch code 34는 재사용하지 않는다.
   기존 미커밋 reviews/2026-10-14-start-wrist-context.json은 보존 중이며 최종 트리 정리 때 출처를 대조한다.

## 실측/계정 확인의 완료 조건

원래 승격 목표(20세트·3일·150회, 90% 정확 일치/95% ±1/MAE≤0.3, 경계/피드백 지연)를
낮추거나 fixture 통과를 실측으로 승격하지 않는다. 실제 Watch8 원본/영상/사람 확인 자료와
실제 로그인 계정 응답이 필요하다. 사용자 승인 업데이트→사용 후 문제 보고 흐름에 따라 진행하되,
최종 전체 목표를 실물 정확도 확인 없이 완료 처리하지 않는다.
