# 현재 운동 감지기 평가와 측정값 표시 정리 (2026-10-13)

> 아래 본문은 최초 v3 평가 시점의 기록이다. 뒤에 추가한 “측정 수정 이어받기”가 현재 v4 코드와 최종 검사 결과를 설명한다. 초기 표의 컬 오차는 양팔 전체 라벨과 한 손목 횟수를 직접 비교했으므로 정확도 근거로 사용하지 않는다.

## 변경과 범위

- 폰 실측 화면의 워치 수동 버튼 안내를 연결 확인 안내로 수정했다.
- 결과 화면과 실시간 분석 알림에서 알고리즘의 자동 추정값을 확정 또는 사용자 확인으로 표현하지 않는다. 기존 confirmedReps 저장/통신 필드는 읽기 호환과 기록 반영 정책을 위해 유지한다.
- 신뢰도 % 대신 내부 분석 점수 /100을 표시하고, 정확도나 정답 확률이 아니라는 안내와 횟수 수정 안내를 추가했다. 수동 입력 우선 정책과 기존 수행 기록은 유지한다.
- 임계값 대비 크기를 관절 가동범위 %처럼 보이게 하던 relativeRom/wristMotionRange 산출을 중단했다. 과거 기록도 결과 화면에 그 비율을 표시하지 않는다. 기존 원본과 과거 저장 필드는 지우지 않았다.
- COMBINED_MOTION 감지기의 혼합 신호를 rad/s로 표시하지 않는다. 순수 자이로 신호만 손목 관측 각속도로 표시한다. 과거 혼합 신호 기록도 화면에서 숨긴다.
- 결과 행 생성은 SetAnalysisUi.resultMetricRows 한 곳에서 처리하여 화면, 호환 표시 검사, 스크린샷이 같은 표현을 사용한다. 감지 기준이나 점수의 자동 기록 반영 임계값은 조정하지 않았다.

## 현재 감지기를 실제로 호출한 재생 평가

CurrentDetectorEvaluationTest는 기존 실측 도구의 WristTrajectoryTracker 대신 현재 워치 코어의 ProfiledMotionRepDetector/ForearmRotationRepDetector와 폰의 PhoneRepAnalyzer를 호출한다. 환경변수가 없으면 공개 데이터 평가 항목은 명시적으로 건너뛴다. 합성 동작/수동 입력/센서 결합/샘플 누락/시간 반올림 검사와 공개 데이터 평가를 분리한다.

센서 결합은 자이로 이벤트 시점의 최근 가속도를 사용한다. 폰 캡처는 서비스의 밀리초 기반 20ms 수집 간격을 근사하며, 선택된 원본 센서 시각은 바꾸지 않는다. 서로 다른 시간 기준과 OS 지연을 실제로 검증하는 것은 아니다. 부동소수점 CSV 변환의 128ns 수준 오차 때문에 정상 20ms 샘플을 추가로 버리지 않도록 선택용 시계만 밀리초로 반올림한다. 센서 값 보간으로 공백을 채우지 않는다.

재현(프로젝트 루트, JDK17/Gradle 환경 설정 후):

```sh
KCOACH_MMFIT_DIR="$PWD/work/datasets/mmfit-probe" gradle :core:test --tests '*CurrentDetectorEvaluationTest' -PcoreOnly --rerun-tasks
```

상세 결과: core/build/current-mmfit-sets.csv (세트별 정답/워치/폰/자동 반영 후보/내부 점수/신호 품질). 원본 CSV와 앱 저장 데이터는 수정하지 않았다. 기존 MmFitEvaluationTest에는 과거 실측 도구 평가라는 표시를 추가했다.

## 해석할 때 지킬 조건

이 데이터는 [MM-Fit 프로젝트](https://mmfit.github.io/)의 왼손 TicWatch Pro 기록이며 실제 Watch8 검증이 아니다. [원 논문](https://vradu.uk/publications/UbiComp2020.pdf)의 Fig.1/Table1에 가속도 m/s², 자이로 rad/s와 워치 100Hz가 기재돼 있다. 데이터셋에서 컬은 양팔 교대 동작이므로 전체 세트 라벨과 한 손목 관측 횟수의 정의가 다를 수 있다. 그 차이를 사후에 유리하게 보정하지 않고 원 라벨 비교임을 표시했다.

총 616세트 중 앱에서 자동 관측을 허용하는 303세트만 횟수 오차를 계산한다. 수동 정책인 313세트는 N/A이며, 0회 추정이나 정확한 0오차로 계산하지 않는다. 이번 재생에서 자동 반영 후보가 된 세트는 0개다. 이를 데이터셋에서의 현 점수/품질/조건 결과로 해석하며 모든 실제 운동에서 자동 반영이 불가능하다고 단정하지 않는다.

세트 경계는 정답 라벨로 주어진다. 시작 준비 게이트, 목표 횟수 자동 종료, 통신, 개인 보정, 화면 꺼짐, 실기기 정확도는 이 재생 평가에 포함되지 않는다. 과거에 탐색한 데이터셋을 다시 사용했으므로 독립된 미사용 검증 데이터 결과라고 부르지 않는다. 워치 레인은 원본 자이로 시각에 따른 코어 검사여서 Watch8 서비스의 실제 샘플링/스케줄링을 재현한 것이 아니다.

기존 실측 도구의 높은 비율을 현재 운동 앱 성능으로 옮겨 말할 수 없다. 이번 결과는 현재 기본 프로필의 취약성을 보여주며, 알고리즘 정확도를 개선했거나 실제 자세/속도/가동범위를 검증했다는 뜻이 아니다.

## 고정한 기준으로 얻은 결과

# Current 워치짐 detector replay — MM-Fit left wrist

616 labelled sets, 21 sessions. Frozen app profiles; no tuning in this evaluation.
Watch lane: current detector at source gyro times. Phone lane: current PhoneRepAnalyzer, latest-accel/gyro pairing, 50Hz capture admission using a rounded-millisecond clock proxy; original timestamps and gaps retained, no interpolation.
Known set boundaries are supplied. Preparation gate, target-rep auto-stop, transport, personal calibration, and Watch8 hardware are NOT evaluated.
Previously explored public dataset; this is NOT independent held-out validation. MM-Fit alternating-arm curls use dataset labels unchanged; whole-set counts may differ from wrist-side counts.
Manual-only exercises stay N/A. Auto-selected means the internal score passed the app threshold, NOT user confirmation or proven accuracy.

| exercise | sets | watch MAE | watch ±1 | phone MAE | phone ±1 | auto-selected sets | selected MAE | selected ±1 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| bicep_curls | 59 | 4.49 | 5.1% | 4.63 | 1.7% | 0/59 | N/A | N/A |
| dumbbell_rows | 64 | 2.84 | 17.2% | 2.48 | 26.6% | 0/64 | N/A | N/A |
| dumbbell_shoulder_press | 60 | 4.50 | 15.0% | 3.27 | 26.7% | 0/60 | N/A | N/A |
| jumping_jacks | 57 | N/A | N/A | N/A | N/A | 0/57 | N/A | N/A |
| lateral_shoulder_raises | 56 | 3.84 | 12.5% | 2.93 | 19.6% | 0/56 | N/A | N/A |
| lunges | 62 | N/A | N/A | N/A | N/A | 0/62 | N/A | N/A |
| pushups | 65 | N/A | N/A | N/A | N/A | 0/65 | N/A | N/A |
| situps | 65 | N/A | N/A | N/A | N/A | 0/65 | N/A | N/A |
| squats | 64 | N/A | N/A | N/A | N/A | 0/64 | N/A | N/A |
| tricep_extensions | 64 | 1.34 | 65.6% | 1.59 | 59.4% | 0/64 | N/A | N/A |

MAE = mean absolute repetition-count error. Selected-only accuracy must always be read alongside selection coverage.

## 변경 후 확인

- core 124항목 중 123통과/공개 데이터 평가 1건 건너뜀, phone 132통과, wear 39통과: 일반 검사 294건 통과. 별도 환경변수를 지정한 재생 실행에서는 공개 데이터 616세트를 실제로 평가했다.
- core의 알려진 3회 합성 동작을 현재 감지기와 PhoneRepAnalyzer에 연결하는 검사가 통과했다. 이는 실제 운동 정확도 검증이 아닌 재생 경로 검증이다.
- 폰·워치 OTA 축소 빌드와 Roborazzi 통과. result.png와 result_unknown.png를 직접 확인하여 자동 추정/확인 필요/분석 점수 표시, 미측정과 실제 0의 구분, 퍼센트 가동범위 부재를 확인했다.
- 저장과 전송 규약의 이름/형식, 자동 기록 반영의 점수 기준, 사용자 수동 입력 우선 정책, 과거 기록의 해석은 바꾸지 않았다.
- 작업 폴더의 변경이며 아직 커밋/push/공개 배포하지 않았다. 폰 앱의 안내·표시 수정은 새 폰 업데이트를 배포한 뒤에 기기에서 보인다. 버전 번호도 기존 0.7.3/0.2.4로 유지했다.

## 측정 수정 이어받기 (현재 v4, 2026-10-13)

- 코덱스1의 사용량 제한으로 완료 보고가 없는 상태를 이어받았다. 기존 변경과 UI 수정은 보존했다.
- `FLEXION_EXTENSION`은 시작 손목 기울기에서 벗어났다 돌아오는 한 주기를 세는 `ReturnToPoseRepDetector`를 사용한다. 고정 자이로 축의 두 움직임을 묶어 세는 이전 방식에서 바뀌었다. 60도 이탈/20도 복귀는 임시 히스테리시스이며 관절 각도나 Watch8 검증 기준으로 해석하지 않는다. 다른 동작 계열의 감지기는 유지했다.
- 워치 서비스는 실시간 판정에 쓴 샘플을 판정 전에 저장한다. 콜백 처리 시각의 20ms 제한으로 배치 샘플을 버리지 않고, 마지막 반복을 만든 샘플도 종료 전에 포함한다. 동일 시각 중복은 저장하지 않는다.
- 이어받은 전체 검사에서 운동 프로필 검사 하나가 버전 `v3`를 고정해 실패했다. 실제 선택한 동작 계열의 알고리즘 ID와 저장 버전의 일치를 검사하도록 수정했다.
- 추가 재현에서 1초 동안 가속도가 전부 0인 구간을 정상 왕복과 이어서 1회로 세던 결함을 발견했다. 중력 추정이 450ms 이상 유효하지 않으면 반복 후보를 폐기하고 출발 위치 복귀 후 재개한다. 기존 시간 공백 기준과 일치하며, 짧은 동적 가속도는 후보를 즉시 폐기하지 않는다. 0.2초 일시 공백은 유지하고, 1초 공백에 걸친 부분 반복은 제외하며, 뒤의 정상 2회는 워치 코어와 폰 모두 다시 센다.
- 느린 하강, 연속 빠른 컬, 중간/꼭대기 멈춤, 위쪽의 작은 반전, 워치 축 변경, 센서 시각 공백, 유효하지 않은 가속도 구간을 검사했다. 실제 서비스 5회 → 저장 KCS → 폰 재분석 5회와 저장 샘플 수/시각 검사가 통과했다.
- 마지막 소스 수정 후 core 136 / phone 142 / wear 42 = **320건 통과**, 실패/오류/건너뜀 0. 공개 MM-Fit 616세트 평가를 포함한다. 자동 관측 대상 303세트에서 워치/폰 횟수 불일치는 0이다. 이는 일관성 검사이며 정답 정확도 검증과 별개다.
- 폰·워치 OTA 축소 빌드와 해당 빌드의 lint 통과. UI 변경도 함께 포함된 현재 작업 폴더의 새 APK다. 실행 로그: `work/measurement-final-current-tree.log`; 검사 수·소스/APK SHA-256: `docs/reviews/2026-10-13-measurement-resume-verification.json`.
- 폰 0.7.3/워치 0.2.4 번호 유지. 커밋/push/공개 배포/기기 설치 없음. 실제 Watch8 센서·개인 정답 횟수·단절/복구·화면 꺼짐 검증은 미실행이다. OneDrive에서 개인 `probe-*.csv`를 찾지 못했다.
- 공개 기록에서 로우/프레스/레이즈의 낮은 성능이 남아 있다. 컬은 양팔 전체 라벨과 한 손목 횟수 정의가 달라 정확도 수치를 N/A로 처리했다. 현재 수정이 모든 운동에서 정확하거나 삼성헬스보다 낫다는 결론은 내리지 않는다.

### 현재 재생 결과 (초기 v3 표를 대체)

# Current 워치짐 detector replay — MM-Fit left wrist

616 labelled sets, 21 sessions. Frozen app profiles; no tuning in this evaluation.
Watch lane: current detector at source gyro times. Phone lane: current PhoneRepAnalyzer on the SAME samples the watch counts; original timestamps and gaps retained, no callback-time throttling or interpolation. Sensor request remains 50Hz; source MM-Fit data is 100Hz.
Known set boundaries are supplied. Preparation gate, target-rep auto-stop, transport, personal calibration, and Watch8 hardware are NOT evaluated.
Previously explored public dataset; this is NOT independent held-out validation. Alternating-arm biceps curls have BOTH-arm labels: their single-wrist accuracy is N/A, not an error against the total label. Per-set estimates remain in CSV for inspection (MM-Fit paper section 5.3).
Manual-only exercises stay N/A. Auto-selected means the internal score passed the app threshold, NOT user confirmation or proven accuracy.

| exercise | sets | watch MAE | watch ±1 | phone MAE | phone ±1 | auto-selected sets | selected MAE | selected ±1 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| bicep_curls | 59 | N/A | N/A | N/A | N/A | 59/59 | N/A | N/A |
| dumbbell_rows | 64 | 2.84 | 17.2% | 2.84 | 17.2% | 47/64 | 2.85 | 14.9% |
| dumbbell_shoulder_press | 60 | 4.50 | 15.0% | 4.50 | 15.0% | 22/60 | 4.14 | 18.2% |
| jumping_jacks | 57 | N/A | N/A | N/A | N/A | 0/57 | N/A | N/A |
| lateral_shoulder_raises | 56 | 3.84 | 12.5% | 3.84 | 12.5% | 55/56 | 3.73 | 12.7% |
| lunges | 62 | N/A | N/A | N/A | N/A | 0/62 | N/A | N/A |
| pushups | 65 | N/A | N/A | N/A | N/A | 0/65 | N/A | N/A |
| situps | 65 | N/A | N/A | N/A | N/A | 0/65 | N/A | N/A |
| squats | 64 | N/A | N/A | N/A | N/A | 0/64 | N/A | N/A |
| tricep_extensions | 64 | 3.44 | 57.8% | 3.44 | 57.8% | 27/64 | 0.85 | 85.2% |

MAE = mean absolute repetition-count error. Selected-only accuracy must always be read alongside selection coverage.
