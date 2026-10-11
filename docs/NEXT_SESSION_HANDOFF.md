# KCoach 다음 세션 인수인계 (2026-10-12)

답변은 반드시 한국어로. 먼저 `docs/PLAN.md`를 읽는다.

## 위치와 도구

- 프로젝트: `~/Documents/Default Project/K코치` (GitHub 공개 레포 `kmnoh12/kcoach`)
- 빌드 도구: `~/Documents/Default Project/안드로이드-에뮬레이터` (jdk17, gradle-8.11.1, android-sdk, AVD `KCoachPhone`·`Wear_Round`)
- 배포: 버전 올리고 커밋 → `tools/release.sh "변경 내용"` → GitHub Releases. 폰·워치 앱이 앱 안에서 인터넷으로 업데이트한다. ADB 불필요.
- 서명: 이 Mac의 `~/.android/debug.keystore` (잃으면 앱 재설치 필요, 백업 미완)

## 현재 버전

- 폰 0.7.0, 워치 0.2.1 (둘 다 R8 축소 ota 빌드)

## 지금까지 한 것

1. 인터넷 업데이트(GitHub Releases) 전환 완료, 실제 폰·워치 설치 확인.
2. 1단계 실측 모드: 워치 → 폰 100Hz 스트림, 손목 3D 궤적, CSV 기록. 실측 결과 화면 꺼짐에도 100Hz 유지 확인.
3. 궤적·반복 알고리즘(`core/WristTrajectoryTracker.kt`): MM-Fit 공개 데이터 검증 — 숄더프레스·로우·레터럴·트라이셉 97~100%, 컬 51%(약점). 재현 방법은 `docs/research/2026-10-11-단계별-조사.md` 부록.
4. 플릭(Fleek) 분석: blutter로 복원, 동작 명세서 `work/re/fleek/FLEEK-SPEC.md` (로컬 전용, git 제외).
5. 폰 운동 화면을 Compose로 재작성(`phone/.../ui/`): 화면 내 키패드, 휴식 링 패널, 다음 세트 배너, 3·2·1 진동, 밀어서 삭제+되돌리기, 세트별 휴식. 에뮬레이터에서만 검증.

## 다음 할 일

- Kyungmin이 실제 운동에서 0.7.0 사용 후 피드백 반영
- 홈·루틴·기록 화면도 같은 모션 규칙으로 Compose 전환
- 컬 반복 인식 개선(실측 CSV: OneDrive `probe-*.csv`, `core` 테스트 `ProbeReplayTest`로 재생)
- 워치 앱 UI 개편, 실측 데이터 기반 측정 방식 확정(2단계)
