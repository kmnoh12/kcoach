# 클로드 → 코덱스2: 카메라 졸라맨 가이드 main 반영 보고

2026-10-15. 사용자가 클로드와 기획한 "카메라 측정 전 가상 졸라맨 모범 예시" 1~3단계를
로컬 main에 fast-forward로 반영했다. 커밋은 `34abba2`이고, 코덱스2 커밋 `aaece43` 바로 위에 있다.
push와 배포는 하지 않았다. 배포는 사용자 지시에 따라 코덱스2가 맡는다.

## 들어간 것

- `core/.../CameraCaptureGuide.kt`: 팔꿈치 각도 종목(컬·로우·랫풀·프레스·푸시다운)의 필수 관절에서
  골반을 뺐다. 상반신 셀카에서 허리 아래가 잘려도 시작 자세가 통과한다. 레터럴 레이즈와 스쿼트는 그대로다.
- `core/.../CameraPoseGuide.kt` (새 파일): 자세 유형 17개로 신호등 졸라맨을 만든다.
  워치 쪽 어깨를 기준점으로 삼고, 크기는 측면에서 위팔 길이, 정면에서 어깨 폭으로 맞춘다.
  표준 인체 비율로 그리고, 위치는 평활 처리한다. 하체 MANUAL 7종목은 가이드를 띄우지 않는다.
- `phone/.../CalibrationCameraActivity.kt`: overlay만 바꿨다. 관절 점(흰 점 + 민트 테두리)을 넣었고,
  졸라맨은 사용자 골격 뒤에 그린다. 기존 `poseReady` 각도 판정이 통과하면 초록으로 바뀌고 체크가 뜬 뒤
  0.6초 후 사라진다. 촬영 중에는 숨긴다. 판정 로직과 저장 계약은 바꾸지 않았다.
- `docs/CAMERA_GUIDE_REDESIGN_PLAN.md`: 전체 계획. 4단계는 카메라 화면 Compose 개편, 안내 1~2줄, 설정 시트다.

## 검사

- core 전체 272개 통과(새 테스트 7개 포함). `aaece43` 위에서 `:phone:compileDebugKotlin`도 통과했다.
- 폰 단위 테스트 중 이 화면을 다루는 것은 없다. 실기기 확인은 아직 하지 않았다.

## 코덱스2가 알아 둘 것

- 코덱스2의 작업 중 파일(`MainActivity.kt` 수정분, `MainResultNavigationTest.kt`)은 건드리지 않았다.
  fast-forward로 클로드 파일만 바뀌었다.
- 4단계 설정 시트는 코덱스2의 `WorkoutWearingStore`(착용 손·화면 방향)를 기본값으로 읽을 계획이다.
  저장소 형식을 바꾸면 알려 달라.
- 다음 배포에 이 커밋을 포함할 때는 촬영 화면에서 졸라맨이 표시되는지 실기기로 확인해야 한다.
- 이전 빌드 정리 때 클로드가 `gradle --stop`을 실행했다. 같은 Gradle 8.11.1 데몬을 쓰는 코덱스2 빌드가
  그 시점(약 15:33)에 끊겼을 수 있다. 이후 클로드는 `--no-daemon`으로만 빌드한다.

## 추가 반영 (b5407ab, 8e920fd 위 fast-forward)

- 플라이 3종목과 덤벨 풀오버에 카메라 시작 판정을 넣었다. 판정은 어깨 기준 각도로 한다.
  기준값은 임시이며 실제 촬영으로 확인하지 않았다.
- 케이블 익스터널 로테이션은 회전각을 판정하지 않는다. 팔꿈치가 약 90°로 1초 유지되면 시작 가능으로 본다.
- 케이블·로프 오버헤드 익스텐션 졸라맨을 덤벨 오버헤드와 분리했다.
  익스터널 로테이션도 전용 자세를 쓴다.
- `CalibrationCameraActivity`의 시작 판정은 `guide.startReady()`와 `guide.readyHoldMs`로 바꿨다.
  수동 확인 체크박스와 시작 버튼 제거, 자동 시작은 4단계 화면 개편에서 한다(시안 선택 대기).
- core 274개 통과, `:phone:compileDebugKotlin` 통과. 실기기는 미확인이다.

## 4단계 반영 (376f0b8, b5407ab 위 fast-forward)

- 카메라 화면을 Compose(`ui/CalibrationCameraScreen.kt`)로 바꿨다. 카메라 화면에서는 착용 손·화면 방향·시작 방향을 묻지 않는다.
  착용 정보는 `WorkoutWearingStore.defaultSelection()`에서, 시작 방향은 `preparationAnswers()`에서 읽는다.
  패턴 후보 시험(intent `watchOnLeft`/`watchOrientation`)일 때만 넘겨받은 값을 쓴다.
- 시작 버튼과 시작 자세 확인 체크박스를 없앴다. 시작 판정이 1.5초 이어지면 자동으로 촬영을 시작한다.
- `MainActivity`·착용 저장소 형식은 건드리지 않았다. 코덱스2 작업 중인 `MainActivity.kt` 변경과 겹치지 않는다.
- 검사: core 275개 통과. 새 `CalibrationCameraScreenTest` 6개 통과(폰·커버 10:16·안쪽 3:4 스크린샷 포함).
  폰 전체 테스트·lint·OTA와 실기기 확인은 하지 않았다. 최종 통합 검사 때 같이 확인해 달라.

## 클로드를 CLI 워커로 쓸 때 (2026-10-15 운영 전환)

사용자 결정: 대화 창구는 코덱스2 앱 하나다. 코덱스2가 총괄·폰 백엔드·최종 통합·배포를 맡는다.
코덱스1(워치·통신)과 클로드(폰 프론트엔드·UX·카메라 흐름)는 코덱스2가 CLI로 운영한다.
클로드 데스크톱 세션은 여기서 종료한다.

1. 기존 데스크톱 세션(`17148649-9090-462c-ab2c-e21a87eaa2c1`)을 resume하지 말고 `claude -p`로 새로 부른다.
   대화가 매우 길어 resume하면 느리고 비싸다. 필요한 맥락은 이 문서와
   [CAMERA_GUIDE_REDESIGN_PLAN.md](CAMERA_GUIDE_REDESIGN_PLAN.md)에 있다. 같은 세션을 앱과 CLI에서 동시에 열지 않는다.
2. `claude -p`는 승인 버튼을 누를 수 없다. `--permission-mode acceptEdits` 등 권한 옵션을 지정해 부른다.
   Gradle은 항상 `--no-daemon`으로 실행하게 지시한다. `gradle --stop`은 같은 버전을 쓰는 다른 에이전트 데몬까지 끈다.
3. 클로드 작업은 별도 worktree에서 한다. 기존 브랜치 `claude/camera-guide`(폴더 `워치짐-camera-guide`)는
   main `376f0b8`까지 반영돼 있으므로 이어 써도 되고 새 브랜치를 지정해도 된다.
   카메라 1~4단계의 폰 전체 테스트·lint·OTA·실기기 확인은 통합 때 필요하다.
