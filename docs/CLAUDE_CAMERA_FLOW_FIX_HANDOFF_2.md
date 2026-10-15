# 클로드 → 코덱스2: 카메라 흐름 수정 2차 (브랜치 `claude/camera-guide-2`, main 57caf8f 위)

수정: `CalibrationCameraActivity.kt`, `CalibrationCameraScreen.kt`, 테스트 2개. 각도·센서·계약·저장소·버전 변경 없음. push/배포 안 함.

1. 후보 시험 intent의 착용 값이 잘못되면 기본값으로 바꾸지 않고(기존대로 null), 화면에 "후보의 워치 착용 정보가 올바르지 않아요"와
   "뒤로 가서 후보 시험 다시 선택" 버튼(뒤로 나감)을 보여 준다. 설정 링크는 계속 없음.
2. 착용 정보가 없으면 졸라맨 초록/체크와 상태 글자의 ready 색을 내지 않는다(`matched`, `ui.ready`에 `wearing != null` 조건). 포즈 판정 자체는 그대로.
3. 테스트: `CalibrationCameraScreenTest.invalidCandidateWearingOffersBackNotSettings`,
   `CalibrationCameraActivityTest.candidateWithUnusableWearingLeavesThroughBackInsteadOfSettings`(실제 Activity, ActivityScenario).

통합 검사 대상: `:phone:testDebugUnitTest --tests 'dev.kyungmin.kcoach.phone.ui.CalibrationCameraScreenTest' --tests 'dev.kyungmin.kcoach.phone.CalibrationCameraActivityTest'`
Gradle은 실행하지 않았다. Activity 테스트는 컴파일·Robolectric 구동 여부 미확인이라 실패하면 그 테스트만 손봐야 할 수 있다.
autoStart 재무장·권한 설정 진입 회귀 테스트는 카메라 프레임/권한 런처 결과가 필요해 만들지 않았다(실기기 확인 필요).
