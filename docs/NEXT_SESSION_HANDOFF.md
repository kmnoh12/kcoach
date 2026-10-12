# KCoach 다음 세션 인수인계 (2026-10-12, 폰 0.7.1 배포 후)

답변은 반드시 한국어로. 먼저 `docs/PLAN.md`를 읽는다.

## 위치와 도구

- 프로젝트: `~/Documents/Default Project/K코치` (GitHub 공개 레포 `kmnoh12/kcoach`)
- 빌드 도구: `~/Documents/Default Project/안드로이드-에뮬레이터` (jdk17, gradle-8.11.1, android-sdk, AVD `KCoachPhone`·`Wear_Round`)
- 배포: 버전 올리고 커밋 → `tools/release.sh "변경 내용"` → GitHub Releases. 폰·워치 앱이 앱 안에서 인터넷으로 업데이트한다. ADB 불필요.
- 서명: 이 Mac의 `~/.android/debug.keystore` (잃으면 앱 재설치 필요, 백업 미완)

## 현재 버전

- 폰 0.7.1(배포됨; 이후 작업은 미배포), 워치 0.2.1 (둘 다 R8 축소 ota 빌드)

## 지금까지 한 것

1. 인터넷 업데이트(GitHub Releases) 전환 완료, 실제 폰·워치 설치 확인.
2. 1단계 실측 모드: 워치 → 폰 100Hz 스트림, 손목 3D 궤적, CSV 기록. 실측 결과 화면 꺼짐에도 100Hz 유지 확인.
3. 궤적·반복 알고리즘(`core/WristTrajectoryTracker.kt`): MM-Fit 공개 데이터 검증 — 숄더프레스·로우·레터럴·트라이셉 97~100%, 컬 51%(약점). 재현 방법은 `docs/research/2026-10-11-단계별-조사.md` 부록.
4. 플릭(Fleek) 분석: blutter로 복원, 동작 명세서 `work/re/fleek/FLEEK-SPEC.md` (로컬 전용, git 제외).
5. 폰 운동 화면을 Compose로 재작성(`phone/.../ui/`): 화면 내 키패드, 휴식 링 패널, 다음 세트 배너, 3·2·1 진동, 밀어서 삭제+되돌리기, 세트별 휴식. 에뮬레이터에서만 검증.

6. 홈·하단 탭·기록·통계·메뉴·루틴 상세를 Compose로 전환(폰 0.7.1). 파일: `ui/RootScreens.kt`(홈, 탭 바, `pressable`), `ui/TabScreens.kt`(기록·통계·메뉴), `ui/RoutineDetail.kt`(길게 눌러 순서 변경). 탭 전환 200ms 페이드, 상세 슬라이드 300ms PanelEasing. 에뮬레이터에서만 검증, 실제 폰 체감 미확인.
7. 컬 인식 조사: MM-Fit에서 "경로 8cm 미만 구간은 반복으로 안 센다" 기준이 컬 51→61%, 스쿼트 81→86%, 숄더프레스 97→95%. 앱엔 아직 미적용(평가 테스트 환경변수 `KCOACH_MIN_PATH_M`만 추가). 정답 횟수·영상이 있는 새 컬 기록이 있어야 원인 확인 가능.

8. 디자인 개편(미배포, 로컬 커밋 9개): 민트 포인트 `#2dd4bf`, 모서리 6px(`KShape`), 구분선 목록, 홈 요일 막대(높이 = `core/TrainingLoad`: 시간은 70분 만점, 세트는 25세트 기준)와 "일째 쉬는 루틴" 블록. 왼쪽 색 줄·알약·반복 카드·그라데이션은 쓰지 않는다(AI 티). Gym+(라임 포인트, 꽉 찬 추천 카드)와 겹치지 않게 할 것.
9. 운동별 기록 저장소(`core/ExerciseHistory`, `phone/ExerciseHistoryStore`): 운동을 끝내면 `recordHistory`가 오늘 세트를 저장. 이걸 바탕으로 운동 화면에 "지난번", "다음 목표"(`NextTargetAdvisor`, 이유 문장 포함, 탭하면 적용), 메뉴의 "지난 세트 불러오기", 운동 기록 화면의 최근/최고 기록이 나온다. 세트 아래 센서 한 줄(`SetInsight`, 템포·후반 속도)은 코드만 연결, 워치 없이는 못 봄.
10. 운동 대체는 동작 기준(`core/ExerciseSubstitutes`, `ExerciseDefinition.pattern`), 라이브러리는 부위 > 동작 > 변형 접기(`core/ExerciseTaxonomy`, `ExerciseDefinition.family`). 새로 넣은 운동(바벨 로우, 랫 풀 다운 변형 등)은 이미지가 없어 빈 칸으로 보임.
11. 확인 못 한 것: 워치 연동 보정 흐름, 마이마운틴 화면, 센서 한 줄, 모션의 실제 체감(스크린샷으론 판단 불가). 에뮬레이터는 부하가 높으면 앱이 25초쯤 걸려 뜨니 탭 사이 대기를 넉넉히.

## 다음 할 일

- 폰 화면은 거의 전부 Compose로 전환 완료(0.7.1 이후 작업은 로컬 커밋, 미배포): 바텀시트·다이얼로그(`ui/Overlays.kt`), 라이브러리(`ui/LibraryScreen.kt`), 운동 정보·기록(`ui/ExerciseScreens.kt`), 보정·마이마운틴·완료 결과(`ui/MiscScreens.kt`), 자유운동은 `RoutineDetailScreen` 재사용. 남은 View: 운동 화면 위 라이브 패널(`buildLivePanel`), `exerciseAnimationView`(GIF), `ProbeActivity`, 화면 껍데기 `showScaffold/showDetail/showStandalone`. 실제 폰 체감 미확인.
- 4단계 디자인 레퍼런스 정하기 (루틴 상세 위 285dp 빈 박스는 인체 그림 placeholder)

- Kyungmin이 실제 운동에서 0.7.0 사용 후 피드백 반영
- 홈·루틴·기록 화면도 같은 모션 규칙으로 Compose 전환
- 컬 반복 인식 개선(실측 CSV: OneDrive `probe-*.csv`, `core` 테스트 `ProbeReplayTest`로 재생)
- 워치 앱 UI 개편, 실측 데이터 기반 측정 방식 확정(2단계)
