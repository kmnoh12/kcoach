# 카메라 보정 후속 수정 인계 — 2026-10-16

사용자가 한도가 다 되어 여기서 중지하고 다른 에이전트에 인계하라고 요청했다. 이 파일 작성 후 추가 구현·검사는 중지했다.

## 시작하는 사람에게

프로젝트 `/Users/kmnoh/Documents/Default Project/K코치`, main, HEAD `ea7bcfc`.
이 세션 변경은 **전부 미커밋**이다. `git diff`와 untracked 소스·테스트를 함께 읽어라. 버리거나 덮어쓰지 말 것.
기존 미푸시 커밋 c940fc2 / 9b67dfb / ea7bcfc도 그대로다. push/공개/설치/버전 변경은 하지 않았다.
폰 버전은 여전히 0.2.1 / code 40. 최신 수정 전체의 lint·OTA·최종 전체 검사는 아직 하지 않았다.
진행 중 Gradle 검사 프로세스는 인계 직전 확인에서 없었다.

사용자가 원하는 가장 급한 결과: 10회 촬영 후 폰에 걸어가 수동 종료하지 않아도 되게 하고, 종료 직후 뜨는 복잡한 ‘반복 구간 확인’ 화면을 일반 사용자용 횟수 확인 화면으로 바꾸기.
기본 확인 없이 다음 구현을 계속해도 되지만, 공개는 완료된 결과를 보여 준 뒤 사용자 확인이 필요하다.

## 원래 요청과 유지할 조건

1. 목표 10회 고정. 감지기 횟수로 촬영을 종료하지 않음. 시작 자세 복귀 후 2~3초 정지로 종료. 종료 전 접근 동작을 학습에 넣지 않기. 워치 종료 가능 여부 검토.
2. 큰 ‘10회’, 자동 영상 재생, 손목 높이 그래프 하나와 반복 후보, ‘10회 했나요?’ [맞아요][9회][11회][기타]. 답은 countReference. 복잡한 라벨링 화면은 연구 모드로 숨기기.
3. 프레임 성능은 실측부터. fd.sync / 메타 재읽기 / Kotlin YUV 변환이 의심되지만 단계별 수치 없이 최적화하지 않기. frameIndex 연속·camera timestamp 단조 증가·분석 프레임만 인코딩·videoFrameWritten·종료/실패 저장 보장 유지.
4. WorkoutTempoSettingsActivity 먼저 Compose 디자인 시스템으로 옮김. 나머지 구식 화면은 연구 모드로 둘지 결정.
5. visibility 0.65, 외회전 각도 75~105°, 가장자리 범위 등 기존 인식 기준은 실측 없이 낮추거나 통합하지 않기. 스쿼트 반대쪽 관절, 완전 측면 외회전, 두 졸라맨 실기 화면은 별도 확인.
6. AppNotIdleException으로 전체 폰 화면 검사가 실패하는 원인 수정.

한국어는 docs/DESIGN.md의 해요체·명사형 및 core KoreanToneTest 준수. 에뮬레이터는 삭제됨. 실기기 또는 Robolectric/Roborazzi만 사용.

사용자는 자동 라벨 완화 설명을 어려워했다. 쉽게 설명한 뒤 ‘그래’라고 답했으므로 **사람의 정렬 확인·확인된 반복 최소 3회 기준을 유지**하는 쪽으로 진행했다. 횟수만 일치한다고 반복 경계를 사람 확인 정답으로 바꾸지 않는다. 현재 코드도 그 기준을 유지한다.

## 구현한 것 (완료 확정 아님)

### 종료 제어

- 새 core/CameraCaptureEnd.kt: 카운트 없이 실제 관절 이동을 한 번 관측한 뒤, 시작 자세와 처음 위치로 복귀해 2.5초 정지하면 종료.
- CameraCaptureGuide.startReady/visible을 재사용. 외회전도 metric 없이 동작하도록 관절 위치를 함께 본다.
- 새 정지 제어 허용치 normalized image 0.02, 관측 간격 500ms 초과 시 hold 리셋. 이는 **새 제어 휴리스틱**이며 실기기 성공률이 검증된 값이 아님. 기존 visibility/각도 기준은 안 바꿨다.
- ‘최초 위치로 복귀’가 너무 엄격한지, 2.5초 미만의 세트 중 멈춤이나 가림에 안전한지 추가 검토·실기 필요.
- CameraActivity가 워치에 보내던 8회를 TARGET_REPS=10으로 수정. 화면 안내는 ‘10회 후 시작 자세에서 3초 멈춰 주세요’.
- motionEndCameraNs를 capture metadata에 저장. 원본 영상·센서·프레임 시각은 보존. 재생·반복 후보와 학습 데이터 view에서 정지 꼬리 제외.
- CalibrationCaptureStore.motionDocument는 검증된 시간 매핑 없으면 실패하도록 구현. 보간/경계 coverage를 위해 종료 경계 직후 첫 센서 샘플 하나까지 보존한다. **이 마지막 변경 후 전체 테스트 미실행**. 실제 필요/안전성 다시 검토할 것.
- ReviewStore는 motionEndCameraNs를 넘어가는 반복 라벨 거부. PatternCandidateStore.readSet은 motionDocument 사용.
- 수동 종료/120초 종료에는 유효한 자동 경계가 없으면 cutoff를 만들지 않음. 기존 촬영의 접근 구간을 자동으로 잘랐다고 주장하면 안 된다.
- 최종 파일/메타데이터 저장 재시도는 기존 finalizer 유지. performance 저장 실패도 pending으로 재시도하게 추가.

### 종료 후 화면·연구 모드

- 새 CalibrationResultActivity + ui/CalibrationResultScreen, manifest 등록.
- CameraActivity 종료 직후와 ‘저장한 촬영’, 메인 보정 화면 저장 촬영 진입을 새 화면으로 연결.
- 10회는 처음에는 ‘촬영 목표’, 답 저장 뒤 ‘확인한 횟수’로 구분.
- VideoView 자동 재생, 종료 경계에서 반복 재생, 일시정지/처음부터, 재생 위치 그래프 커서와 반복 후보 표시.
- 실제 영상 playback은 Robolectric에서 검증 못함. UI 검사에서는 영상 없음 상태만 렌더했다.
- CameraRepProposals가 없는 외회전에는 가짜 반복 구간을 그리지 않음. 그래프 손목 높이는 실제 좌표.
- 답 저장은 saveCount만 호출. saveCycles/alignmentReviewed 자동 생성 없음.
- 목표나 워치와 횟수가 다르면 ‘다른 횟수의 구간 확인’ 버튼 제공. 자동으로 구식 화면을 열지 않음. 제목 길게 누르면 연구 상세 진입.
- 메인 보정 화면의 센서·3D 버튼도 제목 길게 누르는 연구 진입 뒤에만 보임. PatternCandidates/StartDirection/Probe는 이 연구 동선에서만 접근하게 남겼고 Compose 전환하지 않음.
- 구식 ReviewActivity만 system bar inset도 보완. 나머지 연구 화면의 inset/스타일 전면 수정은 미실행.
- 새 ResultActivity의 loading/failure/회전/백그라운드 복귀, VideoView crop/repeat의 실기 확인 필요. 저장 클릭은 선택 capture와 표시 clip ID가 같아야 하도록 방어함.

### 템포 설정

- WorkoutTempoSettingsActivity를 Compose로 교체, ui/WorkoutTempoScreen 추가.
- safeDrawingPadding/imePadding, KColors/KShape, 기존 입력 제한·다음 세트부터 반영·저장 로직 유지.
- 입력/오류/정상 저장 UI 테스트 추가. Switch는 Material3 기본 모양 그대로라 docs의 알약 금지 규칙과 맞는지 검토 여지 있음. 아직 별도 변경하지 않음.

### 성능 자료 보관

- FramePerf를 촬영 시작에 reset. 완료한 1초 구간 요약을 최대 180개 보관.
- 종료 finalizer에서 performance.json 저장, ZIP에 포함. 결과 제목 길게 누르면 요약 열람 가능.
- 본 촬영의 인코딩·저장 병목은 분리 측정 자료가 없어 **큐/스레드/fsync/캐시 최적화 안 함**.

## 사용자가 보내 준 실제 자료

동일 촬영 ID 끝자리 5f3dd894, external_rotation / 케이블 익스터널 로테이션, 왼손.

영상 없는 ZIP:
`/tmp/codex-remote-attachments/01a13f61-f53b-73c0-8817-25d09a666992/f45e786b-7f7c-418e-9d64-ff9be554c1b0/1-kcoach-evidence-5f3dd894.zip`
영상 포함 ZIP:
`/tmp/codex-remote-attachments/01a13f61-f53b-73c0-8817-25d09a666992/394a3723-2fb6-4470-9f79-56f3f4e4ae0d/1-kcoach-evidence-5f3dd894-1-.zip`

프로젝트 내 로컬 확인용으로 capture.json / camera-frames.jsonl / reference.mp4만
`work/camera-field-5f3dd894/`에 풀어 둠. 개인 영상이므로 **git에 넣거나 공개하지 말 것**.
영상 포함 ZIP의 7개 entry의 bytes/SHA256을 evidence-manifest.json과 대조해 일치 확인.

실제 계산:
- 217프레임 / 25.418621875초, (N-1)/span = 8.497706959fps.
- 카메라 간격 평균 117.68ms, 중앙값 100.07ms, p95 166.79ms, 최대 266.86ms.
- receivedNs → completedNs 평균 66.10ms, 중앙값 64.48ms, p95 88.41ms, 최대 107.42ms.
  **변환+모델 완료까지**이며 순수 모델 시간만이라고 하면 안 됨. 인코딩·저장 시간은 이 기존 ZIP에 없음.
- 217장 모두 videoFrameWritten=true, 시각 역전 0, 매 프레임 단일 인물.
- 왼쪽 어깨 11·팔꿈치 13은 217/217, 손목 15는 215/217에서 min(vis,pres)>=0.65.
- 시작 뒤 자료이므로 완전 측면에서 시작이 안 되는 이유를 확정하지 못함.
- review revision=0, count 없음, 구간 라벨 없음. 실제 10회 정답·완전한 반복 구간은 새로 라벨하지 않음.

claude-real-video 스킬로 로컬 영상 전처리만 사용, 음성 전사 안 함.
`/tmp/kcoach-field-video-5f3dd894/`는 기본 dedup으로 3장만 남아 동작 확인에 부족.
`/tmp/kcoach-field-video-motion-5f3dd894/`는 --fps-floor 0.5 --dedup-threshold 0, 34장/4 contact sheets.
MANIFEST 및 contact sheets 4장을 모두 직접 봤고 마지막 동작 뒤 폰으로 다가오는 것은 확인.
반복 10개의 정확한 시작·전환·끝 시각을 눈으로 라벨한 것은 아님.

실제 Kotlin CameraCaptureEnd에 217프레임 재생:
`FIELD_END frames=217 cutoffVideoSeconds=null decisionVideoSeconds=null`.
영상도 마지막에 충분한 2.5초 정지가 없이 접근하는 모습. 따라서 이번 기존 자료로 자동 종료 성공을 주장하지 않음.
새 안내대로 마지막에 3초 멈추는 실기 촬영이 필요하다고 사용자에게 설명함.
외부 재생용 CameraEndFieldReplayTest는 KCOACH_CAMERA_FIELD_DIR가 없으면 명시적으로 skip.

## 테스트 실패 원인과 변경

기존 전체 폰 검사에서 첫 Compose AiCalibrationUiTest가 AppNotIdleException으로 60초 대기하는 문제 재현.
- work/phone-fail-fast.log: 282 tests, 1 failed, 1 skipped, 총 1분36초(failFast로 이후 중지).
- work/idle-minimal-repro.log: WorkoutResultIntentTest + AiCalibrationUiTest만으로 재현. 12건 중 1 failed / 1 skipped, 1분7초.
- AiCalibrationUiTest + CalibrationCameraActivityTest + CalibrationCameraScreenTest 조합은 같은 프로세스에서도 통과(work/ui-pair-check.log).
- 같은 실패 조합을 forkEvery=1로 각각 프로세스 분리하면 통과(work/idle-isolated-check.log).
- 관측상 원인은 ActivityController로 MainActivity의 ComposeView를 띄우는 테스트와 Compose rule의 프로세스 상태 공유. 정확히 어떤 static/dispatcher가 남는지까지 증명하지는 못함. 앱의 무한 애니메이션으로 확정하지 말 것.

phone/build.gradle.kts 변경:
- 새 testDebugCompose task에 **/ui/** 및 CalibrationCameraActivityTest 포함.
- testDebugUnitTest는 이 파일들을 제외하고, testDebugCompose에 dependsOn.
- 따라서 기존 tools/release.sh의 testDebugUnitTest 전체 실행에도 화면 테스트가 실제 포함됨. 우회 제외 아님.
- 새 task에 Android test classpath/system properties/JVM args/빌드 의존성 복사.
- Roborazzi record/compare/verify project property도 직접 전달(처음에는 누락되어 이미지가 안 나왔고 수정함).
- 직접 화면 테스트 대상 실행은 이제 :phone:testDebugCompose --tests ... 사용.
- 기본 testDebugUnitTest에 --tests 데이터 테스트를 줘도 Compose task는 의존성으로 전체 실행됨. 이 부작용/검사 시간 개선 여부는 검토 가능.

## 실제 검사 결과와 한계

1. 초기 core CameraCaptureEndTest 3건 통과. 이후 외회전 test ID를 external_rotation으로 바로잡았음. **바로잡은 뒤 core 전체 미실행**.
2. work/camera-followup-compile.log: 당시 :phone:compileDebugKotlin + :core:test 통과. 이후 구현 변경이 더 있었음.
3. work/followup-target-tests.log: 당시 새 화면·실제 자료 재생·capture/review/export 대상 통과. 이후 변경이 더 있었음.
4. work/phone-isolated-full.log: 변경한 프로세스 분리로 전체 폰 검사 46초 통과. 당시 testDebugCompose 89 / unit 281(실제 자료 env 없어서 1 skip), failures/errors 0.
5. 이후 추가된 finalizer 성능 저장 실패 재시도 테스트 1건은 아직 실행하지 않음.
6. 이후 motionDocument에 끝 경계 직후 첫 센서 샘플 하나 보존 변경함. 이 변경 뒤 전체 데이터 검사 미실행.
7. 최신 work/followup-screenshots.log: 새 ResultScreen 2 + TempoScreen 1 + CameraScreen 7, 총 10건 통과, 11초.
   `-Proborazzi.test.record=true`로 실제 이미지를 만들고 다음 세 장 직접 열어 봄:
   - phone/build/outputs/roborazzi/capture-count-initial.png (영상 없음 상태; 실제 영상 재생 검증 아님)
   - phone/build/outputs/roborazzi/tempo-settings.png (저장 버튼으로 스크롤된 입력 화면)
   - phone/build/outputs/roborazzi/camera_phone_waiting.png (새 10회/정지 안내)
   템포 제목이 있는 최상단 화면은 별도 캡처로 다시 확인 가능. 실기 inset 확인 아님.
8. 최신 전체 core/phone/wear/updates, lint, OTA, JNI 검사 **아직 미실행**.
9. ADB devices -l: 연결 기기 없음. 설치/실기 확인/정확도 검증/새 자동 종료 성공 검증 없음.
10. 인계 전 git diff --check는 통과했으나 마지막 코드를 커밋하지 않았다.

## 다음 진행 순서

1. 미커밋 diff와 untracked 파일을 읽고 종료/저장/라벨 불변조건을 검토. 특히 motionDocument 경계 직후 샘플, 결과 화면 lifecycle/비디오 반복/저장 실패 경로, 새 Gradle 분리를 확인.
2. 위 미검사 항목 포함 core·phone·wear·updates 검사, lint·OTA. 프로세스 분리의 전체 통과는 다시 확인할 것.
3. 원래 릴리스 제외 안내는 docs/FIRST_PASS_RELEASE_020.md. 이번 새 전체 게이트가 끝까지 통과하면 ‘여전히 22개 화면 제외’라고 기계적으로 적지 말고 실제 결과를 기록.
4. 버전은 아직 0.2.1/code40. 다음 공개 버전 선택·상향, 커밋·공개 노트 작성, 공개 전 사용자 확인 필요. tools/release.sh는 커밋된 소스를 요구하며 실행하면 push/공개까지 함. 승인 전 전체 스크립트 실행 금지, Gradle 검사/빌드만 별도로 실행.
5. 워치 종료 검토는 MainActivity.finishCurrentSet이 MANUAL_SUBMIT이고 paired camera STOP 연동은 별도임까지 읽음. ‘워치에서 양쪽 촬영을 정상 종료할 수 있다’는 결론은 아직 못 냄. 이어서 handler/phone path를 대조해 결과 보고.
6. 실기: 마지막 3초 정지 자동 종료·횟수 확인 화면의 영상 자동 재생·새 performance.json 확인. 외회전 완전 측면/45도 비교·스쿼트 측면·졸라맨 2종은 아직 필요. 기존 기준값 유지.

## 환경 명령

```sh
T="$HOME/Documents/Default Project/안드로이드-에뮬레이터"
export JAVA_HOME="$T/jdk17/Contents/Home"
export ANDROID_SDK_ROOT="$T/android-sdk"
"$T/gradle-8.11.1/bin/gradle" --offline :core:test :updates:testDebugUnitTest :phone:testDebugUnitTest :wear:testDebugUnitTest :phone:lint :wear:lint :phone:assembleOta :wear:assembleOta
# 화면 대상/PNG만 확인할 때:
"$T/gradle-8.11.1/bin/gradle" --offline :phone:testDebugCompose --tests '*CalibrationResultScreenTest' --tests '*WorkoutTempoScreenTest' -Proborazzi.test.record=true
# 외부 자료 재생(표준 JVM 테스트에 env가 전달되는지 결과 system-out 확인):
KCOACH_CAMERA_FIELD_DIR="$PWD/work/camera-field-5f3dd894" "$T/gradle-8.11.1/bin/gradle" --offline :phone:testDebugUnitTest --tests '*CameraEndFieldReplayTest'
```

## 후속 작업 결과

이후 로컬 검사·빌드와 추가 보완 결과는 [폰 0.2.2 공개 후보](CAMERA_FOLLOWUP_RELEASE_022.md)에 기록했다. 원래 인계 내용과 기존 미커밋 변경은 보존했다. 이후 사용자 승인으로 `13f66ff`를 커밋·공개했고 p0.2.2-w0.2.0의 latest manifest/실제 APK 해시까지 검증했다. 실기 확인은 여전히 남아 있다.
