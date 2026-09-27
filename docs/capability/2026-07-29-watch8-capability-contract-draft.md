# KCoach Watch8 Capability Contract

- 상태: `APPROVED_FOR_CONTRACT_IMPLEMENTATION / PRE-WATCH8-VALIDATION`
- 작성 기준일: 2026-07-29
- 대상: Wear OS Watch8, 손목 착용 accelerometer + gyroscope
- 이 문서 승인 이후에는 contract enforcement만 구현하며, Watch8 실물 정확도 검증 전에는 정확도 개선/AI 고도화를 시작하지 않는다.

## 판정 규칙

| 등급 | 의미 | 자동 출력 정책 |
|---|---|---|
| A | 손목/전완의 반복 신호가 운동 반복과 직접 연결될 가능성이 높은 후보 | `rep_count` 후보. 단, Watch8 정확도 검증 전에는 provisional |
| B | 기구·그립을 통해 손목에 반복 신호가 전달되는 후보 | 반복수 후보 + 손목 관측 metric만 허용. 해부학적 ROM/form 금지 |
| C | 간접 신호 또는 개인화/setup protocol이 필수인 후보 | `personalized_auto_count_beta`만 허용. confidence 낮으면 manual fallback |
| D | 손목 IMU에서 하체/관절 반복을 안정적으로 관측할 근거가 없는 후보 | `manual_required`; 자동 반복수·form score 생성 금지 |

모든 등급은 최종적으로 `UNVALIDATED → WATCH8_SIGNAL_VALIDATED → WATCH8_ACCURACY_VALIDATED` 승격 절차를 거친다.

## 공통으로 허용되는 관측값

- `rep_count`: 해당 variant의 validation gate를 통과한 경우에만 확정값
- `rep_confidence`: 반복 template/phase/periodicity와 signal quality의 신뢰도
- `wrist_motion_speed`: 손목 신호 기반 상대 속도
- `wrist_motion_range`: 손목 센서 trajectory의 상대 범위
- `trajectory_consistency`: 반복 waveform의 일관성
- `tempo_consistency`: 반복 간 주기 일관성
- `signal_quality`: timestamp gap, dropout, orientation drift, contact violation 등
- set boundary는 rep count와 별도 문제로 처리한다.

## 공통으로 금지되는 주장

손목 IMU만으로 다음을 산출·표시하지 않는다.

- 실제 관절 ROM 또는 무릎/팔꿈치 각도
- 턱이 철봉을 넘었는지
- 가슴 터치 여부
- 허리 중립·견갑 제어·팔꿈치 정렬
- 실제 중량 이동거리·중량 속도
- 근육 수축량 또는 form correctness

## Calibration 기본 protocol

1. 동일 운동 variant, 동일 워치 side, crown orientation, strap tightness, 착용 위치, grip/contact를 고정한다.
2. 2초 stillness를 기록한다.
3. 자연 cadence로 5~10회 controlled repetition을 수행한다.
4. 2초 stillness를 기록한다.
5. gravity/orientation-normalized feature, phase order, min stroke, repetition interval envelope, max rep duration을 저장한다.
6. 느림/보통/빠름 cadence와 non-training interference를 최소 1회 확인한다.
7. grip, watch side, strap position, contact condition이 바뀌면 해당 calibration을 무효화하거나 재보정한다.

풀업·setup-assisted leg press는 위 protocol에 추가 조건이 있다.

## 33개 운동 잠정 matrix

등급은 연구 기반의 **잠정 계약**이며 Watch8 정확도 검증 전 확정 성능을 의미하지 않는다.

| # | exercise_id | 표준 variant 잠정 등급 | 우회/조건부 variant | 지원 출력 | 금지/제한 | fallback |
|---:|---|---|---|---|---|---|
| 1 | `pull_up` | C | `calibrated_same_grip` C-beta; low baseline → 5~8 reps → A→B→A | beta rep count, cadence, template consistency, signal quality | 턱 통과·strict/kipping·견갑·실제 ROM | manual set/reps |
| 2 | `assisted_pull_up` | C | 동일 머신·그립·워치 위치 calibration C-beta | beta rep count, cadence, wrist trajectory | 보조 중량이 바뀌면 기존 template 무효 가능 | manual |
| 3 | `lying_tbar_row` | B | 동일 손잡이·손목 위치 calibration | rep candidate, tempo, wrist motion | 등 수축·견갑·손잡이 실제 이동거리 | manual |
| 4 | `lat_pull_down` | B | 동일 바/그립 calibration | rep candidate, tempo, wrist motion | 가슴 도달·팔꿈치 경로·등 ROM | manual |
| 5 | `seated_row` | B | 동일 손잡이·몸통 자세 calibration | rep candidate, tempo, consistency | 견갑·가슴 접촉·실제 cable travel | manual |
| 6 | `ez_biceps_curl` | A-beta | fixed grip/side calibration | rep candidate, wrist rotation, tempo | 팔꿈치 고정·완전 신전·cheat 여부 | manual |
| 7 | `barbell_biceps_curl` | A-beta | fixed grip/side calibration | rep candidate, wrist rotation, tempo | 몸통 반동·팔꿈치 위치·실제 ROM | manual |
| 8 | `preacher_curl` | B | 동일 패드·그립·손목 위치 calibration | rep candidate, tempo, wrist trajectory | 팔꿈치/상완 접촉·완전 ROM | manual |
| 9 | `incline_curl` | A-beta | 동일 벤치 각도·손목 side calibration | rep candidate, wrist rotation, tempo | 어깨 위치·팔꿈치 ROM | manual |
| 10 | `incline_hammer_curl` | A-beta | 동일 벤치 각도·neutral grip calibration | rep candidate, wrist trajectory, tempo | 상완 고정·실제 팔꿈치 각도 | manual |
| 11 | `dumbbell_pullover` | B | 동일 벤치·양손 grip calibration | rep candidate, trajectory consistency | 어깨 ROM·갈비뼈/허리 보상 | manual |
| 12 | `flat_bench` | B | 동일 grip width·rack/bench protocol | rep candidate, tempo, wrist stability | 가슴 터치·바 경로·견갑 고정 | manual |
| 13 | `incline_bench` | B | 동일 bench angle·grip protocol | rep candidate, tempo, wrist stability | 가슴 접촉·어깨/팔꿈치 정렬 | manual |
| 14 | `chest_press` | B | 동일 seat/handle position | rep candidate, tempo, wrist trajectory | 손잡이 실제 travel·가슴 ROM | manual |
| 15 | `machine_fly` | B | 동일 seat/handle position | rep candidate, tempo, consistency | 가슴 수축·팔꿈치 각도·실제 ROM | manual |
| 16 | `cable_pushdown` | A/B-beta | 동일 attachment·elbow-side protocol | rep candidate, wrist motion, tempo | 팔꿈치 고정·완전 신전 | manual |
| 17 | `cable_overhead_extension` | B | 동일 cable height·grip protocol | rep candidate, tempo | 팔꿈치/상완 정렬·완전 ROM | manual |
| 18 | `rope_extension` | A/B-beta | 동일 rope height·grip | rep candidate, wrist trajectory, tempo | 팔꿈치 고정·몸통 반동 | manual |
| 19 | `rope_overhead_extension` | B | 동일 cable height·grip | rep candidate, tempo | 팔꿈치·어깨 ROM | manual |
| 20 | `dumbbell_triceps_extension` | B | 동일 grip/side, 양손 variant 분리 | rep candidate, tempo | 팔꿈치 각도·상완 위치 | manual |
| 21 | `ez_lying_extension` | B | 동일 bench·grip·elbow setup | rep candidate, tempo | 이마/머리 접근·팔꿈치 ROM | manual |
| 22 | `external_rotation` | B | 동일 cable height·elbow contact | rep candidate, wrist rotation, tempo | 어깨 관절각·상완 고정 여부 | manual |
| 23 | `crossover_reverse_fly` | B | 동일 cable crossover setup | rep candidate, trajectory consistency | 견갑·상완 경로·실제 ROM | manual |
| 24 | `smith_shoulder_press` | B | 동일 seat/back angle/grip | rep candidate, tempo, wrist stability | 바 경로·어깨 ROM·허리 보상 | manual |
| 25 | `machine_shoulder_press` | B | 동일 seat/handle position | rep candidate, tempo | handle travel·어깨/팔꿈치 정렬 | manual |
| 26 | `dumbbell_lateral_raise` | B | 동일 stance·load·wrist side calibration | beta rep candidate, tempo, wrist trajectory | 어깨 관절 ROM·반동·상완 높이 | manual |
| 27 | `one_arm_cable_lateral` | B | side별 별도 calibration, 동일 pulley setup | beta rep candidate, tempo | 상완 높이·어깨 ROM·몸통 기울기 | manual |
| 28 | `machine_reverse_fly` | B | 동일 seat/handle position | beta rep candidate, tempo | 견갑·상완 정렬·실제 ROM | manual |
| 29 | `leg_press` | D | `hand_on_knee_proxy` C-beta; phone-on-leg alternative | proxy beta count, cadence, signal quality | hands-free 상태에서 다리 반복·무릎각·sled travel | phone/manual |
| 30 | `seated_leg_curl` | D | 별도 sensor/contact setup 없으면 없음 | HR, set time, manual record | 다리 반복·무릎각·기구 travel | phone manual |
| 31 | `leg_extension` | D | 별도 sensor/contact setup 없으면 없음 | HR, set time, manual record | 다리 반복·무릎각·기구 travel | phone manual |
| 32 | `hip_adduction` | D | 별도 thigh/leg sensor 없으면 없음 | HR, set time, manual record | 다리 반복·고관절각·기구 travel | phone manual |
| 33 | `hip_abduction` | D | 별도 thigh/leg sensor 없으면 없음 | HR, set time, manual record | 다리 반복·고관절각·기구 travel | phone manual |

## Protocol-specific contracts

### Pull-up `calibrated_same_grip`

- baseline: low/hanging position
- calibration: 5~8 controlled reps
- expected pattern: `A → B → A`
- store: min stroke, min interval, max rep duration, phase duration envelope
- long top pause must not create duplicate rep
- kipping, grip change, watch rotation, or repeated pattern failure lowers confidence and forces manual fallback

### Leg press `hand_on_knee_proxy`

This is not direct leg measurement. It is:

```text
leg motion → hand/arm contact motion → wrist sensor proxy
```

Required:

- same machine and seat position
- same watch side and strap position
- hand remains in light contact with knee/leg
- contact-loss detection
- calibration against manually counted reps
- label in UI as `proxy beta`, never as direct leg measurement

## Validation gate

Before promoting any row to `WATCH8_ACCURACY_VALIDATED`:

- compare against manual/video ground truth on Watch8
- report MAE, exact-count rate, off-by-one rate, missed reps, false reps during rest
- split by watch side, strap position, grip, cadence, load, fatigue, and pauses
- include non-exercise/null segments
- test process restart, screen-off, timestamp gaps, and sensor dropout
- reject automatic output if signal quality or protocol validity fails

## Current implementation implication

The existing generic profiles in `ExerciseTrackingProfiles.kt` are not sufficient for this contract because they collapse multiple variants into `pull-machine`, `press`, `curl`, and `raise`. The next implementation gate is to represent variant/protocol metadata explicitly, but that change remains blocked until this draft is reviewed and accepted as the design contract.
