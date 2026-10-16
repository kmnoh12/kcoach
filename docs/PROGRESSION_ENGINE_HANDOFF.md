# 점진적 과부하 엔진 인계 (2026-10-17)

작업 위치: `워치짐-codex-progression`, 브랜치 `codex1/progression-engine`.

## 이어받은 상태

Codex1의 미커밋 엔진·기록 모델·저장소·테스트와 MainActivity 연결을 검토해 이어서 작업했다. 중단 직전의 저장된 layout 검사, `FinishedWorkoutStore.historyForRecords()`, MainActivity의 이전 기록 비교 패치는 이미 적용돼 있었다. 기존 `/tmp/progression-final.log`의 24개 실패는 전부 Robolectric이 `/Users/kmnoh/.robolectric-download-lock`를 만들지 못해 테스트 본문에 도달하지 못한 환경 오류였다.

## 구현과 정책

- `ProgressionEngine.nextTargets`는 루틴 ID, 운동 ID, 루틴 내 위치, 장비, 순서와 세트 유형이 일치하는 과거 기록만 사용하는 순수 함수다. 성공 횟수는 따로 저장하지 않는다.
- 본세트를 중량별로 묶어 무거운 묶음부터 평가한다. 같은 묶음이 전부 자기 목표를 채우면 앞의 미달 목표 하나를 +1회 한다. 모든 목표가 반복 범위 끝에 도달하면 빠르게 1회, 보통 2회, 천천히 3회 연속 실제 수행을 확인해 묶음 전체를 증량하고 하한으로 돌아간다.
- 무거운 묶음이 자기 목표를 못 채웠으면 성공한 가벼운 묶음을 평가할 수 있다. 증량 확인 중인 묶음은 다음 묶음을 막는다. 자동 감량은 없다.
- 기록은 그날 목표, 웜업, 완료·입력 여부, 안정적인 세트 위치, 반복 범위 끝, 부하 종류, 체중과 진행 판단 가능 여부를 보존한다. 기존 3/5열 및 `weight:reps` 기록은 기본값으로 읽는다.
- 목표 선입력의 적용 출처와 사용자 수정을 저장한다. 성공 카운터가 아니며, 같은 기록·계획을 다시 열어도 추가 증가하지 않는다. 사용자 수정은 동일 회차의 설정 변경·늦은 기록 정정에도 우선한다.
- 기본 증량은 바벨 5kg, 덤벨 2kg이다. 머신·케이블의 관측 간격은 확인 전까지 후보이며 자동 증량하지 않는다. 운동별 확인값을 저장할 수 있다.
- 수동 확정값이 센서보다 우선한다. 센서 신뢰도는 기존 채택 기준 0.72를 사용하며 낮으면 진행을 보류한다. 미완료·미입력 기록은 연속 성공/목표 미달로 세지 않는다.
- 맨몸은 횟수 진행만 자동 수행하고, 어시스트는 보조 중량을 감소시킨다. 가중 맨몸의 신기록/e1RM 계산은 체중+추가 중량을 사용한다. 시간 기반과 미등록 동작 패턴은 진행하지 않는다.
- 신기록은 최대 중량, 같은 무게 최다 횟수, 추정 1RM 순으로 가장 높은 한 종류를 반환한다. 첫 기록·웜업은 제외하며, 더 높은 무게 이력이 있으면 낮은 무게 횟수 갱신을 축하하지 않는다. Epley는 2~10회만 계산한다.

## 이어서 수정한 항목

- 테스트 전용 Gradle init script로 Robolectric 잠금 파일의 홈을 쓰기 가능한 `/private/tmp/progression-robolectric-home`으로 옮겼다. Maven 캐시는 기존 경로에서 읽는다. JDK 자동 탐색의 깨진 경로는 지정 JDK만 사용하도록 고정했고 Kotlin 컴파일은 in-process로 실행했다. 앱 빌드 설정은 바꾸지 않았다.
- `PersonalRecords`가 시작 시간이 없는 레거시 로그를 비교할 때 미래 날짜 기록을 과거 신기록 비교에 섞지 않도록 날짜 상한을 추가했다.
- MainActivity의 신기록 표시 연결에서 같은 무게 횟수는 `회`, e1RM은 kg/lb로 표시한다. 종전의 일괄 최고 볼륨 처리를 제거했다.
- 전체 세트 구성 보존으로 미완료 세트가 저장되는 만큼 `BodyLoad`와 MainActivity의 지난 수행 요약은 완료·횟수 입력 세트만 사용한다.
- 저장된 layout 불일치 시 상한을 인덱스로 물려받지 않는지, 20회 캐시 밖의 완료 기록도 신기록 비교에 쓰이는지 회귀 테스트를 추가했다.
- 기존 `PerformedRepsTest` 3개의 `sets.single()` 가정은 전체 세트 구성 보존 규칙과 맞지 않았다. 이를 단순 필터로 약화하지 않고 두 위치의 존재, 완료/입력 상태, 목표 10회, 실제 6/0회, 실제 볼륨 30kg을 함께 검사하도록 강화했다. 기존 BEST_VOLUME 기대는 제품의 새 3등급 정책에 맞춰 SAME_WEIGHT_REPS로 변경된 Codex1 패치를 유지했다.

## 가정과 문서 충돌 처리

- 최신 확정값인 복합 6~10회, 고립 6~12회를 기본값으로 사용했다. 옛 벤치 5~8회 예시는 core의 명시적 `upperReps=8/lowerReps=5` 입력 테스트로 그대로 검증하며, 기본 벤치는 6~10회다. 원 지시대로 운동별 상한 설정 UI/저장소는 만들지 않았다.
- `ExerciseTaxonomy`의 기존 pattern 어휘로 분류했다. `vertical_pull`, `row`, `chest_press`, `shoulder_press`, `leg_press` 및 스쿼트·데드리프트·런지는 복합, 컬·플라이·레이즈·삼두·레그 컬/익스텐션·힙·풀오버·외회전은 고립으로 취급한다. 모르는 pattern은 목표를 바꾸지 않는다.
- 연구 초안의 비율 제한, e1RM으로 다음 반복을 계산하는 규칙, 느리게의 +1회 대기 규칙은 제품 최종 결정으로 대체됐다. 이를 적용하지 않는다.
- 과거 기록에 루틴 위치/세트 ID가 없으면 비교 가능하다고 추측하지 않는다. 기존 기록은 유지하고 새로운 형식의 첫 비교 회차부터 진행한다.
- 맨몸에서 가중 시작은 자동 생성하지 않는다. 추가 중량 장비의 최소 단위를 모르면 확인 전까지 중량을 유지한다.

## Claude가 연결할 API

- `ProgressionSettingsStore(context).load(): Result<ProgressionSettings>` / `save(settings): Result<Unit>`: 설정과 운동 더보기에서 동일한 값을 읽고 쓴다. 기본 enabled=true, speed=NORMAL.
- `ProgressionSpeed.SLOW/NORMAL/FAST`, `confirmations`: 3/2/1회. 속도는 반복 +1회 대기나 증량 크기를 바꾸지 않는다.
- `ProgressionIncrementStore(context).candidate(exerciseId, equipment, observedWeights): Result<IncrementCandidate>`: `kg`는 후보이며 `needsConfirmation=true`이면 확인 UI가 필요하다. `save(exerciseId, kg)`로 확인값 저장, `clear(exerciseId)`로 기본값 복귀.
- `ProgressionDefaults.upper(pattern)`, `lower(pattern, upper)` 및 `ProgressionSetTarget.upperReps/lowerReps`: 반복 범위 설명용. `upper`가 null이면 미분류다.
- `ProgressionEngine.nextTargets(plan, history, settings, increment)`: 순수 계산. null은 첫 비교 기록 없음/비교 불가/꺼짐/시간 운동이다.
- `ProgressionPlanStore.prefill(routine)`는 MainActivity에서 이미 연결했다. `register(sessionId, routine, sessionSetIds)`는 시작/워치 제안 시 stable routine ID와 snapshot ID를 연결한다. `logs(snapshot, day, analyses)`는 전체 세트 구성을 보존한 기록을 만든다. 폰 모듈 내부 API다.
- `PersonalRecords.detect(history, today): List<RecordHit>`: `HEAVIEST_WEIGHT` 크게, `SAME_WEIGHT_REPS` 작게, `ESTIMATED_ONE_REP_MAX` 아주 작게 표시. `SAME_WEIGHT_REPS.value/previous` 단위는 회, 나머지는 kg이다. `BEST_VOLUME`은 기존 호출 호환용 enum이며 새 엔진은 반환하지 않는다.
- `FinishedWorkoutStore.recordHits(sessionId)` / `historyForRecords()`: 최근 20회 캐시 밖의 완료 기록도 포함해 신기록 비교. 폰 모듈 내부 API다.
- `EstimatedOneRepMax.epley(totalWeightKg, reps)`: 보조 지표, 범위 밖/null/0 이하 중량이면 null.

## UI 담당 작업 / 확인 범위

`phone/.../ui/`, 뒤로가기, 반복 검출기, wear 서비스, 앱 종료·수명주기는 수정하지 않았다. 속도 슬라이더, 마스터 스위치 화면, 핀 확인 질문, 신기록 3등급 축하 크기와 세트 종료 순간 표시, `MENU_TIPS` 3문장 추가는 Claude 담당으로 남긴다. UI 파일 수정 금지에 따라 해당 메뉴 팁을 직접 추가하지 않았다.

실기기/에뮬레이터 설치·실행, 실제 센서 정확도, OTA·릴리스·서명은 수행하지 않았다. 버전 변경·push·커밋도 수행하지 않았다.

## 검증

재현 명령과 최초 전체 실행 로그는 `work/verify-progression.sh`, `work/progression-test-env.gradle`, `work/progression-verification.log`에 있다. `work/`는 gitignore된 로컬 실행 자료다.


### 최종 실행 결과

| 검사 | 전체 | 통과 | 실패 | 건너뜀 |
|---|---:|---:|---:|---:|
| `:core:test` | 329 | 327 | 0 | 2 |
| `:phone:testDebugUnitTest` | 237 | 236 | 0 | 1 |
| 별도 `:phone:testDebugCompose` | 81 | 78 | 3 | 0 |

`:phone:compileDebugKotlin` 통과. 최종 core/phone 일반/컴파일 실행은 `BUILD SUCCESSFUL in 2m 17s`로 종료했다. `KoreanToneTest`, 결정 문서 예시 4개와 기본 6~10회, 첫날 제안 없음, 웜업 제외, 구성/순서 변경, 결정성, 저장 포맷 호환, 수동값 우선, 속도·핀 확인·설정 영속성 검증이 통과했다. core의 `ProgressionTargetsTest` 19개와 폰의 `ProgressionPlanStoreTest` 11개도 모두 통과했다.

최종 로그는 `work/progression-final.log`, 수치 원본은 각 Gradle XML 보고서와 `work/progression-test-summary.json`이다. 전체 Compose 실행은 최종 통계 필터/폰 기록 검증 변경 전 실행 결과이며, 이후 보정 UI/그 테스트는 변경하지 않았고 추가 반복 실행하지 않았다.

건너뛴 테스트는 별도 데이터가 필요하다.
- core: `CurrentDetectorEvaluationTest.replay current watch detector and phone analyzer on labelled MM-Fit sets`, `PeakEngineComparisonTest.compareFrozenPeakCandidateWithCurrentDetectorOnIdenticalOriginalSamples` (`KCOACH_MMFIT_DIR`).
- phone: `CameraEndFieldReplayTest.replayObservedFramesAndReportWhetherTheyContainAnEndHold` (`KCOACH_CAMERA_FIELD_DIR`).

### 남은 실패 (금지된 UI·보정 진단 영역)

`CalibrationResultScreenTest`의 아래 3개다. 전부 테스트가 찾는 옛 문구와 현재 UI 문구가 다르다. 해당 UI와 테스트에 이 브랜치의 diff가 없음을 확인했고 수정하지 않았다. 전체 폰 테스트가 전부 통과했다고 보고하지 않는다.

1. `unalignedWatchLogSaysTheWindowsCannotBeComparedInsteadOfShowingNone`: 테스트는 “워치 기록의 시간을 영상에 맞추지 못해…”를 찾지만 UI는 “센서 재분석 구간을 영상 시간에 맞출 수 없어…”를 표시한다.
2. `aSuspectLastRepShowsANoteWithoutChangingTheCount`: 테스트는 “워치가 센 마지막 1회…”를 찾지만 UI는 “폰 횟수 재분석에서 마지막 후보…”를 표시한다.
3. `mismatchWindowsAreAnsweredPerWindowAndNeverBecomeLabelsHere`: 테스트는 “9.0~11.0초 · 워치에서만 찾은 구간”을 찾지만 UI는 “9.0~11.0초 · 센서 피크 재분석에서만 찾은 구간”을 표시한다.

해당 화면과 검증은 UI/반복 카운트·진단 담당자가 현재 제품 문구를 기준으로 함께 검토해야 한다. 이 작업에서는 우회·제외·완화하지 않았다.

### MainActivity 변경 줄 (최종 파일 기준)

- 51행 주변: 더 이상 사용하지 않는 `ExerciseLog`, `LoggedSet`, `NextTarget`, `NextTargetAdvisor` import 제거.
- 709~711, 1285, 2428행: 루틴 열기·시작·미리보기에서 목표 선입력 연결.
- 1014~1016, 1537~1548행: 신기록 종류별 라벨과 단위 연결. 횟수는 파운드 변환하지 않는다.
- 1311~1312, 1349~1350행: 실제 운동/워치 제안의 snapshot set ID와 루틴 내 안정적인 세트 위치 연결.
- 1550~1563행: 전체 완료 이력으로 이전 신기록 비교, 목표·웜업·전체 세트 구성 보존 저장.
- 1602~1605행: 선입력 저장소 호출과 루틴 저장 연결.
- 1608~1615행: 기존 추천 버튼 데이터를 제거하고 실제 완료·입력 세트만 지난 수행으로 표시. 이전 `plannedReps/applySuggestion` 경로 제거.
- 1623~1624행: 지난 세트 불러오기에서 목표 횟수·웜업 보존.
- 1813~1818행: 남은 모든 세트를 같은 중량으로 덮던 추천 적용 동작 제거.

UI 폴더, 뒤로가기/`onBackPressed`, 반복 검출기, wear 서비스, 버전·서명 설정 변경은 없다. 최종 `git diff --check`도 통과했다.
