# KCoach 다음 세션 인수인계

## 프로젝트

- 프로젝트 루트: `work/kcoach`
- 폰 소스: `work/kcoach/phone/src/main/kotlin/dev/kyungmin/kcoach/phone`
- 워치 소스: `work/kcoach/wear/src/main/kotlin/dev/kyungmin/kcoach/wear`
- 공용 소스: `work/kcoach/core/src/main/kotlin/dev/kyungmin/kcoach/core`
- Gradle: `work/tooling/gradle-8.11.1/bin/gradle`
- JDK: `work/tooling/jdk17/Contents/Home`
- ADB: `work/tooling/android-sdk/platform-tools/adb`

## 현재 상태

- 폰 앱 배포 버전은 `0.4.4` (`versionCode 8`)로 `outputs/update.json`에 등록되어 있음.
- 단, 최근 폰↔워치 동기화 소스 수정본은 아직 배포 APK/update.json에 반영하지 않음.
- 워치 버전은 `0.1.0` 개발 APK이며 자동 업데이트 구조는 없음.
- 최근 워치 APK는 ADB로 임시 설치·실행하여 실제 Watch8 화면을 확인함.
- Watch8 무선 ADB 페어링은 성공했지만 카페 네트워크에서 끊길 수 있음. 집에서 같은 Wi‑Fi로 재연결해야 함.
- 페어링 코드는 만료되므로 다음 세션에는 워치에서 새 `Pair new device` 코드를 받아야 함.

## 구현된 소스

- `core/WearSync.kt`: 폰↔워치 공용 command/state/sample wire format.
- 워치 `MainActivity`: 가속도·자이로·심박수 센서 리스너, 간이 운동별 반복수 추정, 궤적 CSV 저장, 폰 전송, 워치용 UI.
- 워치 `WearCommandService`: 폰 명령 수신.
- 폰 `PhoneWearListenerService`: 워치 state/sample 수신 및 저장/broadcast.
- 폰 `PhoneWearBridge`: 루틴 시작·현재 세트 명령 전송.
- 폰 운동 화면: Wearable connected node 상태를 조회해 `워치 연결됨/대기` 표시하도록 수정.
- 워치가 백그라운드/종료될 때 센서 리스너를 해제하도록 수정함. 삼성헬스 심박수 센서와 충돌하던 문제 대응.

## 중요한 현실 상태

- 실제 폰에는 아직 최신 동기화 APK가 설치되지 않았음. 최신 개발 APK는 빌드 결과 `work/kcoach/phone/build/outputs/apk/debug/phone-debug.apk`.
- 폰 에뮬레이터와 실제 Watch8은 서로 paired Data Layer가 아니므로, 에뮬레이터에서 폰↔워치 실시간 연동을 검증하면 안 됨.
- 실제 연동 검증은 반드시 실제 갤럭시폰에 최신 폰 APK와 Watch8 APK를 각각 설치한 뒤 해야 함.
- 워치 센서 반복수 알고리즘은 현재 프로토타입 임계값 기반이며 삼성헬스 공식 개수 알고리즘을 이식한 상태가 아님.
- Samsung Health 앱 데이터를 읽는 구조가 아니라 워치 센서에 직접 접근하는 구조임.

## 다음 세션 순서

1. Watch8에서 무선 디버깅을 켜고 새 페어링/연결 포트를 받음.
2. `adb pair IP:pairingPort` 후 `adb connect IP:connectionPort`.
3. 최신 워치 APK를 Watch8에 `adb install -r`로 임시 설치.
4. 실제 갤럭시폰에도 최신 폰 개발 APK를 설치. 기존 0.4.3 APK만으로는 새 listener/bridge가 없음.
5. 폰·워치에서 KCoach 실행 → 루틴 시작 → 워치에 운동명/세트/목표가 전달되는지 확인.
6. 워치에서 운동 시작 → 심박수/반복수/센서 sample이 폰 화면에 들어오는지 확인.
7. 삼성헬스 심박수 측정이 KCoach 종료 후에도 정상인지 재확인.
8. 실기 검증 전에는 버전업·outputs 배포·update.json 변경 금지.

## 검증 명령

```bash
cd work/kcoach
JAVA_HOME=/Users/kmnoh/Documents/Codex/2026-07-27/new-chat/work/tooling/jdk17/Contents/Home \
  /Users/kmnoh/Documents/Codex/2026-07-27/new-chat/work/tooling/gradle-8.11.1/bin/gradle \
  :core:test :phone:testDebugUnitTest :phone:lintDebug :wear:lintDebug
```

## 주의

- 카페 Wi‑Fi는 기기 간 통신을 막을 수 있음. 집 Wi‑Fi 또는 모바일 핫스팟 사용.
- ADB는 개발용이며 평소 사용할 필요 없음. 테스트 후 워치에서 ADB 디버깅과 무선 디버깅을 끄기.
- 다음 세션은 이미 연동됐다고 가정하지 말고, 실제 폰에 최신 APK가 설치됐는지와 `adb devices`부터 확인할 것.
