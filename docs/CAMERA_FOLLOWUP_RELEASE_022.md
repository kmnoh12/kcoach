# 카메라 후속 수정 · 폰 0.2.2 공개 후보

상태: 로컬 검사·빌드 완료, 실기 미확인. 사용자가 실기 미확인 범위를 포함한 폰 0.2.2 커밋·공개를 승인했다. 기존 main `ea7bcfc`와 모든 미커밋 변경을 보존했다.
이 문서는 [인계 문서](HANDOFF_CAMERA_FOLLOWUP_20261016.md)의 후속 결과다.

## 사용자에게 보이는 변경

- 촬영 목표는 10회다. 감지기 횟수로 종료하지 않는다. 움직임을 관측한 뒤 시작 자세와 처음 위치로 복귀해 2.5초 정지하면 자동 종료한다. 화면에는 3초 정지를 안내한다.
- 종료 뒤 큰 횟수, 영상 자동 재생, 손목 높이 그래프와 반복 후보, ‘10회 했나요?’ 횟수 확인을 제공한다. 영상 없음/재생 실패, 자료 불러오기/저장 실패와 재시도를 구분한다.
- 횟수 확인은 countReference만 저장한다. 사람의 반복 경계·정렬 확인이나 최소 3회 학습 기준을 자동으로 대신하지 않는다.
- 상세 반복 라벨링과 센서·3D 화면은 제목 길게 누르기로 접근하는 연구 모드에 남겼다.
- 템포 설정은 Compose 디자인 토큰과 사각 체크박스를 사용한다. 기존 입력 제한과 다음 새 세트부터 적용하는 동작을 유지한다.

## 저장과 재생 경계

원본 영상·카메라 프레임·센서 파일·시각은 보존한다. 자동 종료 시 motionEndCameraNs를 별도 저장하고 재생/반복 후보/학습용 데이터에 종료 정지 구간을 제외한다.
검증된 폰 카메라 시계와 폰↔워치 시간 정렬 없이는 센서 데이터를 잘라 학습에 사용하지 않는다.
센서 경계 바로 다음 샘플 하나는 보간/구간 coverage에 필요해서 유지하며 나머지 정지/접근 구간은 제외한다.
수동 종료·제한시간 종료 자료에는 근거 없는 cutoff를 만들지 않는다.
프레임 시각 단조 증가, frameIndex 연속, 분석한 영상만 인코딩, videoFrameWritten 일치, 저장 실패 재시도는 유지한다.
성능 1초 구간 요약은 촬영마다 초기화하고 performance.json으로 저장·내보낸다. 단계별 실기 측정 없이 인코딩/저장 최적화나 인식 기준 변경은 하지 않았다.

## 이어서 보완한 경로

- 결과 화면의 자료 읽기 실패가 ‘아직 촬영 자료가 없어요’로 오해되지 않도록 로딩/오류/재시도를 표시한다.
- 저장 실패 메시지가 다른 촬영 화면에 섞이지 않게 하고, 목록으로 돌아간 뒤 Activity 재생성 시 원래 촬영을 다시 선택하는 오류를 수정했다.
- 영상은 선택한 촬영/경로가 바뀌면 교체하고 이전 재생기를 해제한다. 백그라운드에서는 멈추고 복귀 시 기존 재생 의도를 유지한다. 사용자가 누른 일시정지도 유지한다.
- Compose 규칙 기반 화면 검사와 ActivityController 기반 검사는 별도 JVM task로 나눴다. testDebugUnitTest는 화면 task에도 의존하므로 전체 검사에서 화면을 제외하지 않는다.

## 워치 종료 검토

현재 워치 ‘세트 마치기’는 MainActivity.finishCurrentSet → MANUAL_SUBMIT → WearWorkoutService.submitManualSet → PATH_MANUAL_SET → 폰 sessionController.receiveManual 경로다.
이는 일반 운동의 횟수 제출이며 폰 CalibrationCameraActivity.stopCapture를 호출하지 않는다. 폰 카메라가 종료될 때 보내는 STOP은 반대 방향이다.
따라서 워치 버튼으로 양쪽 촬영을 정상 종료할 수 있다고 안내하지 않는다. 이번 묶음에서는 워치 프로토콜을 확장하지 않았다.

## 검사 결과

2026-10-16 최종 전체 게이트 통과: `work/camera-followup-final-gate.log` (3분 59초).

| 검사 | 전체 | 통과 | 건너뜀 | 실패/오류 |
|---|---:|---:|---:|---:|
| core | 285 | 283 | 2 | 0 |
| updates | 14 | 14 | 0 | 0 |
| phone Compose | 93 | 93 | 0 | 0 |
| phone 데이터/ActivityController | 283 | 283 | 0 | 0 |
| wear | 91 | 91 | 0 | 0 |
| 합계 | 766 | 764 | 2 | 0 |

건너뛴 core 검사는 외부 MM-Fit/원본 샘플을 요구하는 CurrentDetectorEvaluationTest와 PeakEngineComparisonTest다. 폰 화면 검사를 제외하지 않았다.
core:test는 폰 문구 파일의 변경을 Gradle 입력으로 추적하지 않으므로 `:core:cleanTest :core:test`로 다시 실행해 최신 KoreanToneTest까지 통과시켰다 (`work/camera-followup-core-fresh.log`).
폰 데이터 전체 검사에는 KCOACH_CAMERA_FIELD_DIR로 실제 자료를 연결했다. 결과는 `FIELD_END frames=217 cutoffVideoSeconds=null decisionVideoSeconds=null`이다. 자동 종료 성공 증거가 아니다.
추가 검사는 경계 직후 센서 샘플/원본 보존, 미확인 카메라 시계 거부, 성능 기록 초기화/상한, 저장 실패 재시도, 화면 읽기 실패/재시도, 횟수만 저장, Activity 재생성, 영상 교체/해제, 백그라운드 복귀/수동 일시정지 유지/영상 오류 표시를 포함한다.
VideoView 검사는 Robolectric shadow로 호출·수명주기를 확인했다. 실제 디코딩/재생/화면 crop이나 반복 경계의 시각 정확도는 검증하지 못했다.

폰·워치 lint 모두 오류 0, 경고는 각각 176/46개다. 두 assembleOta 통과.
최종 폰 APK의 MediaPipe JNI, Flogger, Protobuf Any 구체 클래스/필드 선언 검사 모두 통과했다 (`work/camera-followup-apk-jni.json`). 네이티브 초기화/포즈 검출 실기 성공을 의미하지 않는다.
aapt2로 최종 APK 버전과 CalibrationResultActivity 선언 확인, apksigner로 두 APK의 기존 SHA-256 인증서 `45130eab69678aeee288c565de466912b97dab48c317cbcf0983430a22c1da2c` 일치 확인.
git diff --check 통과. 공개 승인 전까지 소스와 문서는 미커밋으로 보존했다.

산출물:

- 폰: `phone/build/outputs/apk/ota/phone-ota.apk`, 60,897,138 bytes, SHA-256 `96e631872a93308ce724db00800f2b07f1565ca23d5f0bbdf149379a0282001a`.
- 워치: `wear/build/outputs/apk/ota/wear-ota.apk`, 1,259,491 bytes, SHA-256 `581358a372ccf2a3844b16dc7074aff1de9842282329a311ffb9f678b701f5fc`.
- 검증 메타데이터: `work/camera-followup-candidate.json`.
- 직접 확인한 PNG: `phone/build/outputs/roborazzi/capture-count-initial.png`, `tempo-settings-top.png`, `tempo-settings.png`. 결과 PNG는 영상이 없는 테스트 상태다.

전체 명령은 환경 JAVA_HOME/ANDROID_SDK_ROOT 설정 후:

```sh
KCOACH_CAMERA_FIELD_DIR="$PWD/work/camera-field-5f3dd894" "$GRADLE" --offline :core:test :updates:testDebugUnitTest :phone:testDebugUnitTest :wear:testDebugUnitTest :phone:lint :wear:lint :phone:assembleOta :wear:assembleOta
"$GRADLE" --offline :core:cleanTest :core:test
python3 tools/verify_mediapipe_jni.py phone/build/outputs/apk/ota/phone-ota.apk --mapping phone/build/outputs/mapping/ota/mapping.txt
```

`GRADLE`은 인계 문서의 gradle-8.11.1/bin/gradle 경로다.

## 실기 확인이 남은 범위

연결된 ADB 기기가 없다. 실제 마지막 3초 정지 자동 종료, 실기 영상 자동 재생/종료 경계 반복/복귀, 새 performance.json 수치, 설치/OTA 업데이트는 확인하지 못했다.
새 정지 허용치 normalized image 0.02와 2.5초 정지는 제어 휴리스틱이며 실제 성공률은 미검증이다. 충분히 긴 세트 중 정지도 종료로 해석할 수 있다.
기존 외회전 실측 자료에는 마지막 충분한 정지가 없어 자동 종료 성공 사례로 삼지 않는다.
완전 측면/45도 외회전 비교, 스쿼트 반대쪽 관절, 두 졸라맨 실기 화면도 별도 확인이 필요하다. visibility 0.65와 외회전 75~105도 등 기존 인식 기준은 유지했다.

## 공개 준비

폰 0.2.2/code 41, 워치 0.2.0/code 36. 예정 태그 p0.2.2-w0.2.0.
사용자가 커밋·공개를 승인했다. 현재 소스를 커밋하고 기존 검사 결과·APK SHA-256의 일치를 확인한 뒤 공개한다.
검증한 산출물을 그대로 사용하며 공개 전 JNI와 기존 서명을 다시 검사한다. 실기 설치는 하지 않는다.

공개 노트 초안:

> 폰 카메라 보정은 10회 수행 후 시작 자세에서 3초 멈추면 자동으로 종료하도록 바뀌었어요. 촬영 뒤 영상을 보며 실제 횟수를 간단히 확인할 수 있어요. 템포 알림 설정 화면을 정리하고 촬영 성능 자료와 저장 재시도를 보완했어요. 워치 버전은 유지돼요. 자동 종료와 새 재생 화면은 실제 기기 확인이 아직 필요해요.
