# 워치짐 버그 리포트·추적 목록

작성일: 2026-10-15 · 기준: 폰 0.7.7 / 워치 0.2.7 공개본

## UI-006 후속 — 시작 전 첫 세트까지 민트로 표시됨 (미배포 수정)

현재 진행/직후 휴식 세트만 강조하라는 요구와 대조하며, 미리보기에서도 첫 세트를 현재 세트로
표시하는 조건 누락을 발견했다. 실제 ActiveWorkoutScreen을 렌더링하고 세트 행의 픽셀을 검사했다.
수정 전 첫 세트에서 민트 픽셀 573개가 검출돼 테스트가 실패했고, 실제 운동/휴식의 두 대조 경우는 통과했다.
시작 전에는 현재 세트 강조를 끄고, 하단 상태도 `현재 1세트` 대신 `운동 시작 전 · 총 N세트`로 표시한다.
휴식 중 하단 상태도 다음 실행 순서 대신 실제 완료 세트의 `N세트 후 휴식`으로 일치시킨다.

검사 대상은 WorkoutSetHighlightTest의 실제 화면 3경우, 기존 PreviewClockTest와
WorkoutPresentationTest.restingHighlightUsesCompletedSetIdentityInsteadOfNextExecutionCursor다.
work/preview-set-highlight-after.log에서 대상 6건이 통과했고, 렌더링에서 확인한 휴식 상태 문구의
후속 수정도 work/rest-set-status-after.log의 화면 3경우 모두 통과로 재확인했다.
실패 재현 로그는 work/preview-set-highlight-before.log이며 새 APK/실기 검증과 구분한다.
화면 캡처는 Robolectric 네이티브 렌더링을 사용하며 실제 폰 화면 캡처라고 부르지 않는다.

## 후속 개발 재현 — NAV-001 늦은 분석 수신이 현재 화면을 교체함 (미배포 수정)

aaece43에서 MainActivity의 ACTION_CAPTURE 수신 경로를 재현했다. 활성 운동 없이 완료 기록의
늦은 분석만 수신해도 HOME이 RECORDS로 바뀌었고, 다른 완료 결과를 보고 있어도 최근 결과로 교체됐다.
사용자가 보고한 과거 탭 문제와 동일 원인이라고 단정하지 않는다. 이번에 별도로 확인한 화면 이동 버그다.

- MainResultNavigationTest는 실제 MainActivity를 create/start/resume하고, 저장 분석과 원본 manifest를
  준비한 뒤 실제 BroadcastReceiver의 수신 경로를 호출한다. 수정 전 3개 중 2개 실패:
  `expected:<HOME> but was:<RECORDS>`와 다른 결과 화면의 View 교체가 재현됐다.
- 원인은 ROOT 화면 여부와 최근 세션만 확인하고 현재 탭/열린 결과 ID를 확인하지 않은 것이다.
  현재 탭이 기록이고 열린 결과 ID가 수신 세션과 같을 때만 결과를 다시 표시하도록 좁혔다.
- 수정 후 대상 3개 모두 통과했다. 홈·통계·메뉴·기록 목록/다른 결과를 유지하고,
  열려 있는 같은 결과는 갱신한다. 늦은 분석 저장은 계속 수행한다.
- 로그: work/late-analysis-navigation-before.log / work/late-analysis-navigation-after.log.
  가이드 34abba2 반영 전 빌드 산출물이 남아 있어, 실행 시각만으로 통합 검증을 주장하지 않는다.
  현재 조합의 별도 대상 검사 work/camera-guide-navigation-integrated-focused.log에서
  카메라 12개/수신 경로 3개, 총 15건 통과와 현재 가이드 클래스 생성/폰 컴파일을 확인했다.
  실기기·새 APK·공개 배포는 아직 아니며 전체 테스트/lint/OTA/리뷰 JSON을 반복하지 않았다.

## 후속 상태 — 폰 0.7.8 공개

이후 클로드가 Protobuf 필드 보존 대상을 MediaPipe 패키지에서 모든 GeneratedMessageLite 메시지로
확장하고 폰 0.7.8(code 38) APK를 만들었다. 실제 새 APK에서 Any의 구체 클래스·DEFAULT_INSTANCE·
typeUrl_ 보존과 기존 JNI/Flogger 검사, 기존 설치 서명 일치를 확인했다. 사용자 요청으로
[폰 0.7.8 / 워치 0.2.7](https://github.com/kmnoh12/kcoach/releases/tag/p0.7.8-w0.2.7)을 공개했고,
latest manifest와 실제 다운로드 APK의 SHA-256도 대조했다. 배포 소스는 009fd1d다.
폰 APK SHA-256: `15ad7b7296180b34e828c83406d3cc683558336ea5cc784b46b14ee826dee50c`.
워치는 0.2.7(code 35)의 기존 공개 APK를 유지한다. 실제 폰 초기화·골격 표시 성공은 아직 미확인이다.
아래 본문의 미수정/일시정지 표시는 최초 작성 당시 이력이며 이 후속 상태가 우선한다.

이 문서는 사용자 실기 보고, 개발 중 확인한 사실, 아직 검증하지 않은 판단을 구분한다.
현재 가장 시급한 문제는 **폰의 관절 인식 엔진 초기화 실패**다. 0.7.7에서도 사용자가 실패를 확인했으므로
카메라 인식 기능을 해결·완료 상태로 표시하면 안 된다.

문서 작성 시점에 앱 개발 목표는 사용자 요청으로 일시정지 상태다.
이번 작업은 이 Markdown 작성이며, 아래 수정 제안은 아직 실행하지 않았다.

## 1. 기존 버그 기록의 위치

프로젝트의 `docs`와 README를 확인한 범위에서는 독립적인 버그 목록 파일을 찾지 못했다.
문제와 수정 이력은 아래 문서 및 개별 검사 기록에 흩어져 있었다. 따라서 이 파일을 통합 입구로 사용한다.

- [구현 진행 기록](MEASUREMENT_IMPLEMENTATION_PROGRESS.md)
- [전체 범위 대조](FINAL_SCOPE_AUDIT.md)
- [다음 세션 인계](NEXT_SESSION_HANDOFF.md)
- `docs/reviews/2026-10-14-camera-initialization-hotfix.json`
- `docs/reviews/2026-10-14-camera-initialization-publication.json`

이전 문서의 “검사 통과”, “수정 배포”는 검사한 범위의 결과다. 실제 폰에서 촬영 성공까지 입증했다는 뜻은 아니다.

## 2. 인식 초기화 오류 목록

| ID | 버전 | 사용자 증상·직접 오류 | 확인 상태 |
|---|---|---|---|
| CAM-001 | 0.7.4 | 선명한 전신을 보여도 `관절 인식을 시작하지 못했어요` | 당시 내부 예외는 미확보. 옛 APK에서 JNI 필수 선언 4개 누락을 확인하고 0.7.5에 보존 규칙 반영 |
| CAM-002 | 0.7.5 | `ExceptionInInitializerError` → `no caller found on the stack for: S2.c` | 실제 Flogger/R8 최소 코드로 재현·수정, 0.7.7에 배포. 카메라 기능 전체는 다음 오류로 계속 실패 |
| CAM-003 | 0.7.7 | `Unable to get message info for com.google.protobuf.d` → `Can't instantiate abstract class…` | **현재 미해결.** 사용자의 실제 오류 로그 확보, 해당 APK 매핑 확인. 수정·재현 검사는 아직 안 함 |
| QA-001 | 0.7.7 검사 체계 | JNI/Flogger 정적 검사 통과 후에도 실제 POSE_INIT 실패 | **검증 공백 확인.** 기존 검사는 Protobuf 스키마 생성이나 MediaPipe native 초기화 성공을 검사하지 않음 |

CAM-001의 누락이 당시 실패의 유일한 원인이었다고 단정하지 않는다.
CAM-002의 개별 수정과 CAM-003의 발생을 구분하되, 사용자가 쓰는 촬영 기능은 여전히 실패 상태다.

## 3. 재현 경로와 기대 결과

1. 폰 앱에서 `메뉴 → 측정·보정 → 덤벨 컬 → 워치와 함께 영상 촬영` 진입.
2. 인식 화면을 열고 몸을 비춘다. 초기화 확인에는 실제 운동 반복이 필요하지 않다.
3. 실패 화면에서 `오류 정보 복사` 내용을 확보한다.

기대 결과는 관절 엔진 초기화가 성공하고, 유효하게 관측되는 몸의 골격이 표시되는 것이다.
실제 결과는 `POSE_INIT` 단계 실패다. 단순히 몸의 검출 신뢰도가 낮아 골격이 안 뜬 경우와 구분해야 한다.

확인된 환경은 두 로그 모두 `android_sdk: 37`, `abis: ["arm64-v8a"]`다.
제조사·정확한 기기 모델·전체 Java 스택 트레이스는 이 JSON에 없다.
SDK 숫자만 보고 특정 OS 구현이 원인이라고 결론 내리지 않는다.

## 4. CAM-002 — 0.7.5의 실제 오류 코드

사용자가 제공한 JSON을 내용 변경 없이 들여쓰기만 정리했다.

```json
{
  "version": 1,
  "app_version": "0.7.5",
  "phase": "POSE_INIT",
  "android_sdk": 37,
  "abis": ["arm64-v8a"],
  "causes": [
    {
      "type": "java.lang.ExceptionInInitializerError",
      "detail": ""
    },
    {
      "type": "java.lang.IllegalStateException",
      "detail": "no caller found on the stack for: S2.c"
    }
  ]
}
```

### 확인된 사실·기존 조치

- 보관된 0.7.5 APK DEX의 `S2.c`에서 같은 예외 문자열을 던지는 로거 초기화 코드를 확인했다.
  다른 버전의 `mapping.txt`로 과거 APK의 이름을 역추적하지 않았다.
- 실제 의존성인 Flogger 0.6과 기본 backend를 사용하는 최소 Java 코드가 축소 전에는 실행됐다.
  R8 9.3.16의 classfile 최적화 후 같은 예외로 실패했고, Flogger keep 규칙 추가 후 통과했다.
- 폰의 `proguard-rules.pro`에 다음 규칙을 반영했다.

```proguard
-keep class com.google.common.flogger.** { *; }
```

- `tools/verify_flogger_r8.py`는 해당 초기화 경로의 최소 재현 검사다. 전체 MediaPipe native 실행은 아니다.
- 새 APK 검사에 `FluentLogger.forEnclosingClass()`와
  `StackBasedCallerFinder.findLoggingClass(Class)`의 실제 메서드 보존 확인을 추가했다.
- [MediaPipe 공식 저장소 이슈 6138](https://github.com/google-ai-edge/mediapipe/issues/6138)에는
  같은 예외와 Flogger 보존을 포함한 해결 사례 및 유지보수자의 확인이 있다.
- 0.7.7의 이번 사용자 로그에는 이전 Flogger 예외 대신 아래 Protobuf 예외가 기록됐다.
  이것만으로 모든 기기에서 Flogger 문제가 해결됐다고 일반화하지 않는다.

## 5. CAM-003 — 0.7.7의 실제 오류 코드

사용자가 제공한 JSON을 내용 변경 없이 들여쓰기만 정리했다.

```json
{
  "version": 1,
  "app_version": "0.7.7",
  "phase": "POSE_INIT",
  "android_sdk": 37,
  "abis": ["arm64-v8a"],
  "causes": [
    {
      "type": "java.lang.RuntimeException",
      "detail": "Unable to get message info for com.google.protobuf.d"
    },
    {
      "type": "java.lang.IllegalStateException",
      "detail": "java.lang.InstantiationException: Can't instantiate abstract class com.google.protobuf.d"
    },
    {
      "type": "java.lang.InstantiationException",
      "detail": "Can't instantiate abstract class com.google.protobuf.d"
    }
  ]
}
```

### 현재 확인한 사실

- 0.7.7의 `phone/build/outputs/mapping/ota/mapping.txt`에 다음 대응이 있다.

```text
com.google.protobuf.Any -> com.google.protobuf.d:
com.google.protobuf.GeneratedMessageInfoFactory -> com.google.protobuf.u:
com.google.protobuf.GeneratedMessageLite -> com.google.protobuf.B:
```

- 현재 로컬 OTA APK와 보관된 공개 0.7.7 APK의 SHA-256은 같고, 공개 manifest의 값과도 일치한다.

```text
d7e1300c6e44e059f5ec717213be50242f40e40bbebeabf78535eb3268243737
```

- 현재 규칙은 **MediaPipe 패키지 아래의 생성 메시지 필드**를 보호한다.
  `com.google.protobuf.Any` 자체는 이 패키지 조건에 포함되지 않는다.

```proguard
-keepclassmembers class com.google.mediapipe.** extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}
```

- 0.7.7의 JNI 선언 4개·Flogger 메서드 2개·모델·arm64 JNI 라이브러리 존재는 확인했다.
  그래도 사용자 실기에서 실패했다. 따라서 이 정적 검사만으로는 이번 문제를 잡아내지 못했다.

### 내 판단 — 아직 확정 원인이 아님

가장 유력하게 보는 건 **R8 최적화와 Protobuf의 동적 메시지 초기화 사이의 불일치**다.
구체적으로는 `Any`가 최적화 후 추상 클래스로 취급되는 상황과,
Protobuf가 기본 인스턴스·스키마 정보를 얻는 경로의 조합을 의심한다.
필드만 남기는 기존 규칙으로는 클래스나 초기화 경로의 보존이 부족할 가능성이 있다.

다만 매핑으로 확인한 것은 이름의 대응이다. 그것만으로 추상화 원인이나 삭제된 메서드를 확정할 수 없다.
의존 버전 충돌, Android 런타임 차이도 배제하지 않았다. 이번 예외의 로컬 재현은 아직 없다.
사용자가 함께 붙인 Protobuf 공식 GitHub 관련 해설은 링크를 확인하지 못한 참고 의견이며,
독립 검증 결과로 세지 않는다.

앞선 문제와 **증상 분류는 같은 ‘촬영 초기화 실패’지만, 직접 실패 지점은 다르다**.
현재 오류만으로 운동 횟수 판정 엔진이나 자세 임계값까지 바꿔야 할 근거는 없다.

## 6. 개발 재개 시 진단안 — 아직 실행하지 않음

1. 공개 0.7.7과 같은 APK의 DEX에서 `Any`의 클래스 속성·초기화·기본 인스턴스 획득 경로를 확인한다.
2. 실제 Protobuf·MediaPipe 의존성으로 메시지 생성·직렬화·복원을 수행하는 작은 재현을 만든다.
   축소 전과 현재 R8 규칙 적용 후를 비교해서 이번과 같은 예외가 발생하는지 확인한다.
3. 재현되면 생성 메시지의 클래스와 멤버를 보존하는 아래 후보 등을 하나씩 비교한다.

```proguard
# 검토 후보이며 아직 앱에 추가하지 않았다.
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }
```

4. 개선되지 않으면 protobuf-lite/full runtime 의존성 해석 결과와 실제 초기화 스택을 조사한다.
   필요하면 축소를 끈 진단 빌드로 비교한다. 축소 해제를 곧바로 영구 해결책으로 삼지는 않는다.
5. 수정 후에는 JNI·Flogger뿐 아니라 Protobuf 메시지 초기화도 검사 대상에 포함한다.
   대상 검사부터 진행하고 관련 없는 전체 모듈 검사·lint·OTA 빌드를 중간 단계에서 반복하지 않는다.
6. 새 업데이트 후 실기에서 초기화→골격 표시→짧은 촬영 저장→워치 원본 대응까지 확인한다.
   초기화 성공만으로 횟수 정확도나 개인 보정 개선까지 완료된 것으로 처리하지 않는다.

## 7. 이전 사용자 보고 — 현재 버전에서 재확인 필요

아래는 대화에서 받은 보고와 후속 소스 대조다. **모두 현재도 미수정이라는 뜻은 아니다**.
소스에 수정 경로가 있는 것과 설치된 정확한 APK의 실기 동작은 구분한다.

| ID | 보고된 내용 | 이 문서에서의 취급 |
|---|---|---|
| UI-001 | 리퀴드 하단 탭이 버벅임·홈으로 못 돌아감·이동 중 중간 탭까지 늘었다 줄어듦 | RootScreens/SlidingSelection에 최신 콜백·현재 폭 기준 터치·끝점만 확장하는 코드와 LiquidControlsTest 회귀가 있음. 실제 버벅임/프레임 시간은 별도 실기 확인 |
| UI-002 | 선택 탭 폭이 과함·아이콘이 중앙에 안 보임·조작 시 햅틱 희망 | 선택 영역 96×48dp, 아이콘 경로 중심 보정, 탭/스와이프 햅틱 연결이 소스에 있음. 체감 정렬·진동은 실기 재확인 필요 |
| UI-003 | 세트 상세에서 웜업→일반으로 돌아가지 못함 | SlidingSelection 최신 콜백 경로와 LiquidControlsTest의 왕복/세로 제스처 회귀가 있음. 정확한 설치 버전의 실기 재확인은 별도 |
| UI-004 | 루틴 운동 목록의 ‘…’ 메뉴가 오른쪽 아래에서 올라옴 | OverlayHost의 빈/표시 상태 모두 전체 크기·BottomCenter와 수직 진입으로 고정한 코드가 있음. 실제 폰 전환 재확인은 별도 |
| UI-005 | 루틴에 들어갔을 뿐인데 오른쪽 위 운동 시간이 흐름 | 미리보기의 시계 갱신 중단·00:00 표시와 PreviewClockTest가 있음. 실제 세션 시작 조건과 분리됨 |
| UI-006 | 현재 세트 회색 배경이 입력창과 겹침·이전 세트까지 민트로 강조됨 | 회색 행 배경 제거·직후 휴식 ID/진행 세트만 강조하는 경로가 있음. 후속 미리보기 강조 누락은 위 항목에서 별도 재현·수정. 새 APK의 실기 확인은 별도 |
| WEAR-001 | 워치를 약 20초 안 만지면 앱이 종료되는 것처럼 보임 | 화면 꺼짐·Activity 변화·측정 서비스 종료를 구별해 실기 확인 필요 |

## 8. 완료 판정과 인계

- CAM-003은 제안 규칙을 작성하거나 정적 검사가 통과한 것만으로 닫지 않는다.
- 수정한 정확한 업데이트 버전에서 사용자 실기기가 POSE_INIT을 통과하고 골격을 표시한 결과를 기록한다.
- 카메라 초기화·촬영 저장·횟수 측정·개인 보정 정확도는 각각 별도 확인 항목으로 취급한다.
- 추가 보고에는 앱 버전, phase, 예외 JSON, 조작 순서, 확인한 것과 미확인인 것을 덧붙인다.
- 현재 CAM-003의 코드 수정·새 빌드·새 공개는 하지 않았다. 개발 재개는 사용자 지시를 기다린다.
