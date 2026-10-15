# 코덱스1 워치 1차 실제 구현 인계 — 2026-10-15

기준: main `22f8fbb`를 병합한 `codex/watch-design`의 `472ad84`에서 시작.
코덱스2 총괄 지시의 17:10 KST 첫 반환 범위다. 버전/릴리스/폰 UI/core는 수정하지 않는다.
기존 e25063c는 페이지 전환 기반일 뿐이며 이번 D2 실제 구현이 추가로 필요하다.
통합은 이 브랜치의 누적 변경을 병합한다. 이번 커밋만 별도 cherry-pick하면 이전의
`WorkoutPagesView.kt`도 함께 필요하므로 해당 의존성을 빠뜨리지 않는다.

## 구현

- 실제 MainActivity의 첫 화면을 D2로 교체. 운동명/현재 세트, 큰 횟수와 목표,
  중량, 기존 위치의 심장 기호와 심박 숫자. bpm은 제거.
  `count > targetReps`만 주황, 같은 목표까지는 초록.
- 실시간 상태는 선택한 페이지를 보존. 횟수 아래 작은 스크롤이 아니라
  전체 워치 높이의 부가 조작 페이지를 사용한다.
- 세트 마치기와 횟수 수정을 분리. 수정의 적용은 세트별 로컬 초안만 바꾸고
  완료 명령은 보내지 않는다. 세트 마치기는 기존 수동 outbox/정확한 세트·시도 ID와
  revision으로 COMPLETE 또는 기존 ACK 이후 EDIT를 보낸다. 다른 세트로 바뀐 편집은 전송하지 않는다.
- 준비/휴식/일시정지/수동 필요 상태를 구분. 수동 미입력을 0회로 만들지 않는다.
  휴식 시간은 폰의 절대 restEndAtMs로 표시하고 링은 첫 페이지와 함께 이동.
  다음 세트 요청은 기존 폰 권한 NEXT_SET 계약을 유지한다.
- 워치의 루틴 시작/착용 확인 진입은 없앴다. 폰이 시작한 운동만 표시한다.
  정상 연결 문장·템포 숫자·업데이트 설명을 첫 화면에 나열하지 않는다.
  템포 끄기/복구·미확인 입력/폰 요청 OTA 확인은 부가 영역에 남긴다.
- 전체 COMPLETE 수신 시 정확한 session ID를 서비스에서 별도 보존하고
  런타임 필드 초기화 뒤에도 완료 화면이 유지된다. STOPPED/보정은 완료로 만들지 않는다.
  다음 유효한 운동 시작은 이전 완료 표시만 해제하며 마지막 완료 ID는 남긴다.
- 완료 첫 화면의 동작은 `폰에서 보기`. 버튼마다 새 UUID로
  `ACTION_VIEW + CATEGORY_BROWSABLE + kcoach://workout/result?session_id=...&request_id=...`를 보낸다.
  RemoteActivityHelper future 완료는 전달 요청 결과일 뿐 폰 화면 관찰 증거가 아니다.
  안내는 `폰에서 확인해 주세요`; 실패/15초 응답 없음에는 재시도 가능 안내.
  새 세션 시작이나 Activity 파괴 후의 늦은 callback은 무시한다.
- 버튼 모서리는 폰과 같은 6dp. 조작 버튼 높이 48dp.
  현재 시각은 실제 기기 시각이며 예시 10:09를 하드코딩하지 않았다.

## 폰에 필요한 주간/최근 데이터 계약 — 총괄 연결 요청

워치 수신부까지 구현했지만 폰 송신은 이 묶음에서 구현하지 않았다.
`WearCommandService`는 아래 메시지를 저장한 뒤 UI 갱신 broadcast를 보낸다.
core 공통계약에 상수/codec를 임의 추가하지 않았으며 현재 상수는 WatchHomeStore에 있다.

경로: `/kcoach/watch/overview`, UTF-8 JSON (최대 8192자).

```json
{
  "schemaVersion": 1,
  "generatedAtMs": 1792047600000,
  "weekStartDate": "2026-10-12",
  "weekWorkoutCount": 3,
  "recent": {
    "sessionId": "완료-store의-정확한-ID",
    "routineName": "어깨전문",
    "endedAtMs": 1792040000000
  }
}
```

위 숫자/날짜는 형식 설명용이며 실시간 데이터로 사용하면 안 된다.
- `weekWorkoutCount`: 사용자 로컬 주의 월요일~지금, 폰이 확정 저장한 완료 운동 수.
  계획/미완료 운동 수가 아니다. 실제 0회일 때만 0을 보낸다.
- `weekStartDate`: 폰/워치가 따르는 사용자 로컬 날짜의 해당 월요일 ISO 날짜.
- `recent`: 실제 마지막 완료 운동. 없으면 JSON null. sessionId 규칙은 폰 결과 딥링크와 같다.
- `generatedAtMs`: 원본 요약 생성 시각. 늦게 온 예전 패킷은 거절.
- 폰은 WATCH_UI_READY/재연결/기록 추가·삭제·전체 완료 후 최신 스냅샷을 전송해 주면 된다.
  계정/프로필 전환 때도 새 데이터 또는 명시적 빈 요약을 보내 이전 사용자 기록을 대체해야 한다.
- 수신 전 주간 숫자는 `—`, 최근 안내는 `폰에서 기록을 확인하세요`.
  지난 주 캐시를 이번 주 횟수로 표시하지 않는다. 루틴명은 받은 실제 값만 사용한다.
- 루틴 상세 미리보기 데이터는 이번 계약에 없다. 현재는 폰이 상세 화면을 열어도
  워치가 루틴을 추측하거나 시작 버튼을 만들지 않는다. 필요하면 별도 display-only 계약으로 배정.

## 의존성과 소유 변경

wear/build.gradle.kts: `androidx.wear:wear-remote-interactions:1.1.0` 한 줄 추가만.
Google Maven 공식 source JAR에서 constructor/startRemoteActivity 서명을 확인했다.
공식 AAR metadata의 minCompileSdk=34로 현재 compileSdk=35에 맞는다.
버전 필드/폰 Manifest/폰 MainActivity/phone/ui/core/총괄 운영 문서는 변경하지 않았다.
센서 검진기·축·반복 엔진·wire schema는 바꾸지 않았다.

## 검사 상태와 총괄 실행 요청

이번 턴에 Gradle/단위 테스트/전체 검사/lint/APK/reviews JSON/배포는 실행하지 않았다.
`git diff --check`로 whitespace 검사를 했다. 아래는 **수정/추가한 미실행 대상**이다.
기존 스냅샷/옛 준비 시작 버튼 검사도 최신 UX에 맞게 변경했다.

```sh
:wear:testDebugUnitTest \
  --tests '*WatchD2UiTest' \
  --tests '*WatchHomeStoreTest' \
  --tests '*WatchPhoneResultTest' \
  --tests '*WatchCompletionServiceTest' \
  --tests '*WatchWorkoutPagesTest' \
  --tests '*WatchWorkoutUiTest' \
  --tests '*ManualWatchUiTest' \
  --tests '*ScreenRetentionTest'
```

서비스 종료/복구 영향의 기존 회귀 확인이 필요하면 최종 검사에서
`*SetIdentityTest`도 포함한다.
실제 RemoteActivityHelper → 잠긴/다른 탭/진행 중 폰 화면 → 정확한 완료 결과 표시,
소형 원형 화면 글자/동작, 실제 Watch8 측정·화면 유지·연결 복구는 미확인이다.
실제 테스트/컴파일 성공을 주장하지 않으며 최종 통합 검사에서 확인해야 한다.
