# 폰에서 폰·워치 업데이트 — 2026-10-14

공개 APK는 아직 폰 0.7.5 / 워치 0.2.5다. 아래 내용은 새 로컬 구현이며 실물 설치 검증/새 배포는 아니다.

## 사용자 흐름

1. 폰 메뉴의 `업데이트 확인`이 폰 확인 작업과 워치 확인 요청을 함께 만든다.
2. 각 기기는 자기 manifest와 실제 설치 버전을 비교한다. 최신이면 APK를 다운로드하지 않는다.
   새 버전이 있으면 다운로드·SHA-256·APK 종류·패키지·버전을 검사한다.
3. 폰 메뉴는 두 기기의 상태를 각각 표시한다. 워치 미응답/네트워크 대기를 최신으로 표시하지 않는다.
   워치가 아직 확인/다운로드 중이어도 이미 준비된 폰 설치를 막지 않는다.
4. 설치할 기기만 설치 요청을 진행한다. 둘 다 준비됐다면 워치 요청을 저장·접수한 뒤 폰의 설치
   화면을 연다. 워치 설치 확인은 워치의 운영체제 화면/알림으로 진행한다.
5. 워치의 독립적인 업데이트 확인 버튼은 없다. 폰에서 시작한 상태를 보여주고, 필요한 경우
   그 요청의 운영체제 설치 확인만 다시 열 수 있다.
6. `설치 준비/확인 필요/설치 진행`은 완료와 다르다. 설치 callback뿐 아니라 PackageManager의
   실제 버전이 목표 이상인 것을 확인해야 `설치 확인`으로 표시한다.

## 지속성과 경계

- `updates` 모듈은 두 기기가 공유하는 OTA 경로다. 이전 독립 UpdateManager/WearUpdateManager와
  이를 검사하던 옛 경로의 테스트를 제거하고 새 설치 화면의 권한 복구/파일 검증을 검사했다.
- WorkManager가 다운로드 작업을 저장한다. 네트워크가 없으면 대기하며, 중단된 작업은 같은
  트랜잭션으로 이어간다. 큰 APK 준비/설치 세션 파일 쓰기는 UI 스레드에서 하지 않는다.
- SharedPreferences에 요청 ID·명령 순번·검증한 목표/해시·진행 상태·설치 세션 ID를 보존한다.
  작업 복구가 Application.onCreate보다 먼저 시작될 수 있어 앱 설정도 보존해 읽는다.
- Data Layer의 별도 DataItem 경로 `/kcoach/update/phone-command`, `/kcoach/update/watch-state`를
  사용한다. 운동용 PhoneCommandOutbox/명령/원본 전송에는 업데이트를 섞지 않는다.
- 시작/재연결에서 캐시를 다시 읽고 현재 요청/상태를 재전송한다. 중복 명령은 새 다운로드를
  만들지 않으며 오래된 요청/순번/상태 revision, 다른 워치 응답은 현재 상태를 덮지 않는다.
- 설치 요청은 응답한 워치 인스턴스와 검증된 버전/해시에 묶는다. 두 APK의 packageName이
  같으므로 `OTA_ROLE` metadata와 기기별 asset 이름도 검사해 폰/워치 APK를 구분한다.
- WorkManager에 저장된 작업 클래스 이름은 OTA 축소 뒤에도 유지한다. 기존 공개 APK와
  동일한 서명도 검사한다. 앱 삭제/재설치와 운동 기록 삭제를 요구하지 않는다.
- 알림을 허용하지 않았으면 워치 앱의 진행 상태에서 필요한 설치 확인을 열 수 있다.
  앱이 배경에 있을 때 설치 확인 화면이 자동으로 열린다고 보장하지 않는다.

## 첫 통합 버전으로 넘어가는 순서

현재 공개 워치 0.2.5는 새 요청 경로를 모른다. 폰 소스 변경으로 설치된 구형 워치의 동작을 바꿀 수 없다.
첫 통합 업데이트는 폰의 기존 업데이트와 워치 0.2.5에 남아 있는 기존 확인 버튼으로 각각 설치해야 한다.
두 기기가 통합 버전이 된 뒤부터 폰 한 곳에서 이후 업데이트를 제어한다. 새 버전에 워치 확인 버튼을
남기는 것이 아니다. 새 공개 배포를 준비할 때 두 versionCode를 올리고 이 전환 순서를 안내한다.

## 검증 범위

`updates` 검사에서 폰만/워치만/둘 다/둘 다 최신의 설치 분기, 워치 접수 전 폰 설치 대기,
오래된/다른 기기 응답, 중복 요청, bare 설치 성공 callback의 미확정 처리, 실제 버전 변화 확인,
설치 권한 화면에서 재생성 후 파일 재검증과 권한 거절을 확인한다.
폰/워치의 실제 리스너 진입점도 새 요청·응답을 처리하는지 검사한다.

이는 Robolectric/제어된 입력 검사다. 실제 Data Layer 재연결·다운로드·OEM 설치 화면·화면 꺼짐·
프로세스 종료·설치 완료는 폰/Watch8에서 별도로 검증해야 한다. 소프트웨어 검사와 APK 서명/
DEX 검증을 실물 업데이트 성공으로 바꾸어 보고하지 않는다.

근거: [검사 기록](reviews/2026-10-14-companion-update-verification.json).
관련 전체 목표/남은 범위: [PLAN.md](PLAN.md), [NEXT_SESSION_HANDOFF.md](NEXT_SESSION_HANDOFF.md).

공식 계약: [DataItem 동기화](https://developer.android.com/training/wearables/data/data-items),
[Data Layer 이벤트](https://developer.android.com/training/wearables/data/events),
[지속 작업](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work),
[PackageInstaller 상태](https://developer.android.com/reference/android/content/pm/PackageInstaller#STATUS_PENDING_USER_ACTION).
