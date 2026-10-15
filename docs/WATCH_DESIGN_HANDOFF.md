# 워치 전체 화면 전환 인계

> 2026-10-15 후속 상태: 사용자가 구성 자체를 다시 조사하도록 요청했다.
> 아래 내용은 보존된 첫 구현의 기록이며 최종 구성 확정/배포 준비 완료가 아니다.
> 상용 워치 앱 조사와 새 구성안 검토 전 최종 반영을 보류한다. 앱 소스는 그대로 보존했다.

2026-10-15. 사용자가 이번 대화에서 추가로 승인한 워치 디자인 변경이다.
기존 CODEX1_PARALLEL_TASKS의 세 작업은 이미 main에 통합됐고, 이번 변경은 별도 후속 작업이다.

## 반영 위치

- 브랜치: `codex/watch-design`
- 구현 커밋: `e25063c`
- 시작 기준: main `1022735`
- 적용: `git cherry-pick e25063c` (또는 브랜치 병합)
- 변경 파일: wear의 MainActivity.kt, 새 WorkoutPagesView.kt, 새 WatchWorkoutPagesTest.kt.

기존 횟수 화면 아래의 작은 ScrollView를 제거했다. 첫 화면은 횟수/운동/휴식 링이며,
아래로 넘기면 실제 횟수 입력, 다음 세트/진행 조작, 템포, 심박수, OTA 안내를
두 번째 전체 화면에 표시한다. 두 번째 내용이 길면 같은 전체 영역에서 스크롤한다.
느린 드래그와 플링 모두 페이지 경계에 맞추며, 상태 방송은 사용자가 보고 있는 페이지를 유지한다.
휴식 링은 첫 화면 소속이다. 화면 밖 shadowText는 화면 표시 지연 완료로 보고하지 않는다.
센서/서비스/프로토콜/버전/폰 코드는 변경하지 않았다.

## 확인

소스 컴파일 및 대상 단위 테스트만 수행했다.

- WatchWorkoutPagesTest: 4개, 전체 영역/상태 갱신/휴식 링 분리/플링/느린 실제 터치 이벤트.
- WatchWorkoutUiTest: 2개, 준비 루틴 확인 및 다음 세트 명령.
- ManualWatchUiTest: 1개, 빈 입력과 실제 0회 구별.
- ScreenRetentionTest: 2개, 화면 유지 정책.
- 마지막 추가 검사는 WatchWorkoutPagesTest만 실행했고 4개 통과했다. 총 서로 다른 관련 사례 9개 통과.

검사 명령은 기존 JDK17/Gradle 8.11.1로 `:wear:testDebugUnitTest --tests '*클래스명'`,
max-workers=1, Kotlin in-process, Gradle Xmx1200m를 사용했다.
432px/216dp 원형 기기 설정의 Robolectric native graphics로 실제 Activity 뷰를 렌더링했다.
빌드 결과의 `wear/build/outputs/watch-preview`에 운동/휴식 각각의 두 화면이 생성된다.
미리보기 상태 값은 합성이며 실기기 실행, APK, 설치 또는 Watch8 검증 결과가 아니다.

## 최종 통합

검사 당시 main의 변경은 phone/build.gradle.kts, phone/proguard-rules.pro,
tools/release.sh, tools/verify_mediapipe_jni.py 및 docs/BUG_REPORT.md이며 수정 파일이 겹치지 않았다.
이후 main이 변경되면 병합 시 다시 확인한다.
이번 워치 작업은 공개 배포하지 않았으며, 버전 상승/전체 테스트/lint/OTA 빌드/reviews JSON도 실행하지 않았다.
사용자 지시대로 코덱스2 최종 통합 및 배포 흐름에서 포함한다.
