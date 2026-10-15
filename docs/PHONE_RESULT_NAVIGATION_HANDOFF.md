# 폰의 완료 결과 열기 — 코덱스1 연결용 인계

2026-10-15. 폰 진입/복구 소스 구현과 대상 검사 기록이며 워치 버튼·실제 기기 간 실행의 완료 선언이 아니다.

## 사용자 동작

워치에서 `폰에서 보기`를 명시적으로 누르면 요청한 완료 세션의 결과를 연다.
최근 운동이나 현재 루틴으로 대체하지 않는다. 진행 중인 다른 운동의 측정·휴식·세션/미확인 명령은 유지한다.
원본/분석 도착만으로 다른 화면을 여는 NAV-001과 이 사용자 요청을 구분한다.

## 폰 내부 진입

`WorkoutResultIntent.create(context, finishedSessionId)`가 MainActivity 대상 Intent를 만든다.
ACTION은 `dev.kyungmin.kcoach.action.SHOW_WORKOUT_RESULT`, 세션 extra는 `workoutResultSessionId`다.
NEW_TASK/CLEAR_TOP/SINGLE_TOP으로 기존 task 재사용을 요청한다. 사용자 동작마다 새 요청 식별자를 만든다.
폰 내부 수신/알림 연결은 이 함수를 사용할 수 있다. 이 Intent의 extras를 기기 사이에 그대로 보내면 안 된다.

## 기기 간 VIEW URI 진입

[공식 RemoteActivityHelper 설명](https://developer.android.com/reference/androidx/wear/remote/interactions/RemoteActivityHelper)에 따르면
기기 간 실행은 VIEW/BROWSABLE/data URI를 전달하며 일반 extras·component·package는 제거한다.
따라서 폰 MainActivity에 아래 경로의 VIEW/DEFAULT/BROWSABLE 필터와 URI 파서를 함께 추가했다.
기존 ChatGPT 콜백 `kcoach://chatgpt/return`과 구분한다.

```text
kcoach://workout/result?session_id=<완료 세션 ID>&request_id=<이번 클릭의 UUID>
```

폰의 `WorkoutResultIntent.createRemoteView(finishedSessionId)`가 이 형태를 만든다.
워치 모듈은 phone 모듈을 참조하지 않으므로 코덱스1이 같은 URI 형식으로 연결한다.
Intent action은 VIEW, category는 BROWSABLE, data는 위 URI다. 세션 ID를 extra에만 싣지 않는다.
request_id는 사용자 클릭마다 새로 만들며 완료 세션 ID와 혼동하지 않는다.
실제 워치 버튼/RemoteActivityHelper 의존성·호출/성공·실패 표시와 기기 간 실행은 코덱스1 담당으로 남는다.
이번 변경은 기존 WearSync 메시지나 센서 전송 계약을 바꾸지 않았다.

## 읽기와 복구

- 정확한 FinishedWorkoutStore 완료 스냅샷과 저장된 분석을 읽어 결과를 구성한다.
  미완료 세션/없는 ID/잘못된 ID·타입/중복 session_id는 현재 화면을 유지하고 잘못된 결과로 대체하지 않는다.
  VIEW URI에서는 URI의 세션 ID가 기준이며 임의 extras가 이를 덮지 않는다.
- onCreate/onNewIntent 모두 처리한다. 보던 결과는 화면 재생성·재개/기록 복구 뒤에도 같은 세션으로 복구한다.
- 처리한 요청 식별자는 Activity 저장 상태에 보존한다. 결과에서 홈으로 나간 뒤 재생성되어도 옛 요청을
  다시 실행하지 않는다. 연속으로 서로 다른 결과를 열었던 경우도 포함한다.
  새 사용자 요청은 같은 결과여도 다시 열 수 있다. onNewIntent로 전달된 명시적 클릭도 처리한다.
- 다른 완료 결과를 열어도 최근 운동 캐시를 그 과거 결과로 덮어쓰지 않는다.
  늦은 분석은 보고 있는 같은 결과만 갱신하며 다른 탭/다른 세션으로 이동시키지 않는다.
- 연결된 기기가 실제 화면을 열었는지, 잠금/백그라운드/다른 Activity의 task 처리와
  Watch8 버튼의 끝까지 동작은 단위 테스트로 입증하지 않았다. 최종 통합/실기 확인이 필요하다.

## 검증

현재 진입이 없던 소스에서 WorkoutResultIntentTest 6건 중 5건 실패를 확인했다.
첫 연결 뒤에는 떠난 화면으로 옛 요청이 재실행되는 재생성 오류와 연속 요청 재실행도 각각 재현해 수정했다.
최종 work/result-intent-integrated-focused.log는 아래 대상 14건 통과다.

- WorkoutResultIntentTest 10: cold/warm·manifest VIEW 해석·extra 없는 URI·정확한 세션·최근 캐시·재개/재생성,
  옛 요청/새 요청·연속 요청·잘못된 요청·진행 중 세션/명령 보존.
- MainResultNavigationTest 3: 수동 요청 없는 늦은 분석의 다른 탭/다른 결과 보존과 현재 결과 갱신.
- WorkoutPresentationTest의 기존 미확인 명령/세션 revision 보존 1건.

초기 실패 로그는 work/result-intent-before.log와 result-intent-consecutive-before.log다.
전체 테스트/lint/OTA/새 reviews JSON을 반복하지 않았다. 새 APK와 공개 배포는 아직 아니다.
이 검사 실행 도중 클로드의 추가 카메라 커밋이 main에 들어왔다. 위 대상 결과를 카메라 4단계의
전체 통합/실기 검증 증거로 확대하지 않는다. 전체 구현 후 최종 통합 검사에 함께 포함한다.

## 중지 시점

사용자의 중대사항 전달을 위해 문서 정리 후 일시정지했다. 이 결과 진입 변경과 인계는 미커밋으로 보존한다.
추가 소스 수정/검사/배포는 재개 지시 후 진행한다. 최종 폰 컴파일은
work/result-navigation-current-main-compile.log에서 HEAD 376f0b8 시작/끝 동일 상태로 성공했다.
워치 호출/실기 확인/최종 전체 검사와 공개 릴리스는 남아 있다.
