# 워치짐 Personalized Calibration & Measurement Protocol

- 상태: `DRAFT / DESIGN-GATE INPUT`
- 대상: Wear OS Watch8, 손목 accelerometer + gyroscope
- 관련 계약: `2026-07-29-watch8-capability-contract-draft.md`
- 원칙: calibration은 손목에서 관측 가능한 신호의 개인차를 보정한다. 관측되지 않는 관절·기구 상태를 복원하거나 form correctness를 증명하지 않는다.

## 1. Calibration identity

Calibration은 운동명 하나가 아니라 다음 tuple에 귀속된다.

```text
user_id
exercise_id
variant_id
protocol_id
watch_side
crown_orientation
wear_position
strap_tightness_class
contact_condition
equipment_id_or_setup_hash
grip_id
load_band
calibration_version
created_at
```

다음 값이 달라지면 기존 calibration을 자동 재사용하지 않는다.

- 왼손 ↔ 오른손
- 손목뼈 기준 착용 위치
- crown 방향 또는 워치 회전
- 스트랩 조임 상태
- 바/손잡이/로프 grip
- 기구 seat·back·pulley setup
- hand-on-knee 또는 phone-on-leg contact 여부
- 표준 운동 ↔ proxy protocol

부하(load)는 완전한 무효화 조건이 아니라 별도 `load_band`로 저장한다. 무거운 부하에서 cadence·stroke가 달라지면 confidence를 낮추고 재보정 후보로 만든다.

## 2. 공통 calibration run

### 2.1 준비

1. 사용자가 앱에서 정확한 `exercise_id`, variant, grip, watch side를 확인한다.
2. 워치를 손목뼈 바로 위에 snug하게 착용한다.
3. 워치 회전과 crown 방향을 저장한다.
4. 센서 timestamp와 실제 수신 주기를 확인한다.
5. calibration 시작 전 2초 정지 구간을 기록한다.

### 2.2 반복 수집

- 기본: 5~10회 controlled repetition
- 권장: 첫 2회는 느린 속도, 이후 자연 속도
- 각 반복 사이에 의도적인 긴 정지를 넣지 않는다.
- partial rep, kipping, 의도적 cheating은 calibration에 포함하지 않는다.
- 마지막에 2초 정지 구간을 기록한다.

### 2.3 추출 feature

```text
gravity_estimate
linear_acceleration_magnitude
gyro_magnitude
orientation_or_gravity_projection
primary_axis_or_projection
phase_direction
rep_interval_ms
rep_duration_ms
stroke_amplitude
inter_rep_similarity
stillness_baseline
```

`primary_axis_or_projection`은 전역 고정 axis가 아니라 해당 calibration에서 signal-to-noise가 가장 높은 축 또는 gravity projection으로 선택한다.

### 2.4 calibration acceptance

초기 제안 기준:

- 5회 이상 반복 후보가 검출됨
- 반복 interval의 median 대비 MAD가 지나치게 크지 않음
- 반복별 A/B zone 또는 phase separation이 안정적임
- 첫/마지막 stillness가 구분됨
- timestamp gap 또는 sensor dropout이 없음
- non-exercise 동작에서 반복 pattern이 발생하지 않음

실패 시 저장하지 않고 다음 이유를 보여준다.

```text
CALIBRATION_TOO_SHORT
PHASES_NOT_SEPARABLE
CADENCE_UNSTABLE
WATCH_ROTATION_ORIENTATION_DRIFT
CONTACT_NOT_STABLE
SENSOR_TIMESTAMP_GAP
NOISE_TOO_HIGH
```

초기 연구용 threshold 후보:

```text
calibration_success >= 90%
missed_rep_rate <= 10%
false_rep_rate <= 10%
```

이 값은 제품 acceptance가 아니라 Watch8 pilot에서 조정할 시작점이다.

## 3. 운동별 protocol

### 3.1 풀업 — `pull_up/calibrated_same_grip`

목표는 턱 통과를 판정하는 것이 아니라, 동일 protocol 안에서 반복적인 손목/전완 신호 cycle을 검출하는 것이다.

#### 수행

```text
2초 low/hanging 또는 시작 baseline
→ 5~8회 controlled strict-like repetition
→ 2초 low/hanging 또는 종료 baseline
```

#### primary feature

1. gravity projection 또는 orientation trajectory
2. gyro direction reversal
3. dynamic acceleration magnitude

#### repetition contract

```text
A → B → A
min_stroke >= personalized_min_stroke
min_interval <= interval <= max_interval
phase order must be preserved
long top pause must not duplicate a rep
```

#### 실전 적용

- calibration과 동일한 bar, grip, watch side를 우선 사용한다.
- 세트 중 cadence가 개인 envelope를 벗어나면 count confidence를 낮춘다.
- grip change 또는 watch rotation을 감지하면 beta 상태로 강등한다.
- 하강 후 팔을 내리고 idle posture가 일정 시간 유지되면 set-end 후보를 만든다.
- set-end는 idle posture 하나만으로 확정하지 않고 timeout·pattern 종료·명시적 STOP과 결합한다.

#### 자동 판정 금지

```text
턱이 바 위를 넘었는지
strict/kipping 여부
견갑 하강·후인
팔꿈치 위치
전신 ROM
```

### 3.2 레그프레스 — `leg_press/hand_on_knee_proxy`

이 protocol은 다리를 직접 측정하지 않는다. 손과 무릎의 접촉을 통해 다리 움직임이 손목에 전달되는 proxy다.

```text
leg motion
→ hand/arm contact motion
→ wrist IMU proxy signal
```

#### 준비 조건

- 동일 leg press 기구
- 동일 seat/back position
- 동일 watch side와 착용 위치
- 동일 손을 동일 무릎 위치에 가볍게 접촉
- 세트 중 손이 무릎에서 떨어지지 않음
- 손 위치·접촉 압력·팔꿈치 자세를 최대한 고정

#### calibration

```text
2초 contact-stillness
→ 5~10회 controlled repetition
→ 2초 contact-stillness
```

저장값:

```text
contact_baseline
proxy_stroke
proxy_interval_ms
contact_noise_floor
contact_loss_threshold
```

#### 실전 invalidation

- 손이 무릎에서 떨어짐
- 손을 바꿈
- 워치가 회전함
- seat/setup 변경
- 무릎을 잡지 않고 hands-free로 수행
- contact noise가 calibration 범위를 초과함

이 경우 해당 세트의 자동 count를 확정하지 않고:

```text
PERSONALIZED_AUTO_COUNT_BETA → MANUAL_REQUIRED
```

로 전환한다. UI에는 반드시 `레그프레스 손-무릎 proxy 측정(베타)`라고 표시한다.

#### 금지

```text
무릎 각도
슬레드 이동거리
엉덩이 깊이
다리의 실제 반복수 확정
```

hands-free standard leg press는 기본 `MANUAL_REQUIRED`다.

### 3.3 기구와 손이 결합되는 상체 운동

대상:

```text
lying_tbar_row
lat_pull_down
seated_row
chest_press
machine_fly
cable_pushdown
machine_shoulder_press
```

#### protocol

- 동일 grip과 attachment
- 동일 seat/setup
- 동일 watch side와 crown orientation
- 5~10회 calibration
- 운동별 primary projection 선택
- phase order와 interval envelope 저장

#### 해석

자동 출력은 `hand/wrist observed repetition`으로 정의한다. 등·가슴·어깨의 실제 관절 ROM이나 근육 수축을 의미하지 않는다.

### 3.4 컬·익스텐션·레이즈

대상:

```text
ez_biceps_curl
barbell_biceps_curl
preacher_curl
incline_curl
incline_hammer_curl
cable_pushdown
rope_extension
dumbbell_lateral_raise
one_arm_cable_lateral
```

#### protocol

- side와 grip을 variant로 분리한다.
- 양손 운동과 한손 운동을 같은 template으로 섞지 않는다.
- 팔꿈치 고정 여부는 입력 metadata이지 손목 IMU가 확인하는 값이 아니다.
- 반복 waveform과 phase timing을 저장한다.
- 몸통 반동·partial rep는 자동 count confidence를 낮추지만, strict/form 판정으로 확장하지 않는다.

## 4. Set start/end protocol

반복수와 세트 경계는 별도로 계산한다.

### set start 후보

```text
사용자 START
+ 센서 품질 정상
+ calibration-compatible orientation
+ active pattern 발생
```

### set end 후보

```text
pattern 종료
+ low activity/stillness timeout
+ idle posture 또는 contact release
+ 명시적 STOP
```

다음만으로 set 종료를 확정하지 않는다.

- 단일 저활동 window
- 한 번의 긴 top pause
- 심박 감소
- screen off

자동 종료는 후보 상태를 거친 뒤 확정한다. 사용자가 명시적으로 STOP하면 자동 추정과 관계없이 세트를 닫는다.

## 5. Runtime quality gates

각 세트와 calibration에 다음 quality fields를 남긴다.

```text
sample_count
observed_duration_ms
median_sample_interval_ms
max_timestamp_gap_ms
orientation_drift
sensor_dropout_count
contact_validity
cadence_median_ms
cadence_mad_ms
phase_separation_score
repetition_confidence
fallback_reason
```

다음 조건이면 자동 확정 금지:

- timestamp gap이 허용 한계를 초과
- sensor event가 과도하게 batch/drop됨
- orientation drift가 calibration 범위를 초과
- contact protocol 위반
- phase separation 실패
- confidence가 acceptance threshold 미만

## 6. Watch8 validation protocol

각 운동 variant마다 최소 다음 조건을 분리해 수집한다.

```text
watch side: left/right
strap: normal/tight/loose
orientation: canonical/rotated
cadence: slow/normal/fast
load: light/normal/heavy band
fatigue: early/late set
rep type: full/partial/pause
interference: rest movement/equipment transition
screen: on/off where supported
```

ground truth:

- 수동 카운트
- 가능하면 고정 카메라 영상
- 세트 시작/종료 timestamp
- protocol violation annotation

보고 지표:

```text
MAE per set
exact-count rate
±1-count rate
missed-repetition rate
false-repetition rate during rest
set-boundary error
confidence calibration
fallback rate
```

승격 전까지는 모든 자동 결과를 `provisional` 또는 `beta`로 표시한다.

## 7. 구현 전 결정

이 문서와 capability contract가 승인되기 전에는 다음을 하지 않는다.

- generic profile threshold 튜닝
- AI/ML model 추가
- 자동 form score 확장
- D 운동 자동 count 활성화
- Watch8 정확도라고 emulator 결과를 포장

승인 후 첫 구현 순서는:

1. variant/protocol metadata 모델
2. A/B/C/D resolve와 허용 metric enforcement
3. calibration storage 및 invalidation
4. set boundary와 manual fallback
5. Watch8 raw capture 검증
6. 운동별 알고리즘 개선
