# KCoach wrist-IMU capability contract

이 디렉터리의 계약 문서와 shared core 구현을 함께 읽어야 해. 숫자 정확도는 Watch8 실물 raw IMU 검증 전까지 확정하지 않는다.

## Source of truth

- Contract draft: `2026-07-29-watch8-capability-contract-draft.md`
- Personalization protocol: `2026-07-29-personalized-calibration-protocol.md`
- Shared implementation: `core/src/main/kotlin/dev/kyungmin/kcoach/core/CapabilityMetadata.kt`
- Profile resolution: `core/src/main/kotlin/dev/kyungmin/kcoach/core/ExerciseTrackingProfiles.kt`
- Output model/policy: `core/src/main/kotlin/dev/kyungmin/kcoach/core/Models.kt` and `CapabilityOutputPolicy`

## Capability tiers

| Tier | Contract | Automatic output |
|---|---|---|
| `A_WRIST_DIRECT_AUTO` | 손목 신호에서 직접 관측 가능한 변형 | 검증된 wrist metric과 반복수 |
| `B_AUTO_COUNT_LIMITED_METRICS` | 반복수와 제한된 손목 metric | 손목 관측 범위·속도·템포·신호 품질만 |
| `C_PERSONALIZED_AUTO_COUNT_BETA` | calibration/setup 의존 beta | 실패 시 `manual_phone_input` fallback |
| `D_MANUAL_REQUIRED` | 손목만으로 대상 동작을 관측할 수 없음 | 자동 반복수·손목 운동 metric은 N/A |

## Metric naming rule

허용되는 결과 값은 `rep_count`, `rep_confidence`, `wrist_motion_speed`, `wrist_motion_range`, `tempo_consistency`, `signal_quality`, `phase_timing`, `periodicity`다.

`trajectory_consistency`, `wrist_feedback_score`, `formScore`, `formFindings`, `swayScore` 같은 전신 폼·정밀 궤적 지표는 결과 모델·UI·새 저장 포맷에 포함하지 않는다.

다음은 손목 IMU 결과로 주장하지 않는다: 관절 ROM, 실제 중량 이동거리/속도, 턱 통과, 무릎각, 허리 중립, 견갑·팔꿈치 정렬, strictness.

## Conditional protocols

- `pull_up / calibrated_same_grip`: C beta. 같은 watch side·착용 위치·bar grip, 안정 기준자세 2초 유지 후 5~8 controlled reps.
- `leg_press / hand_on_knee_proxy`: C beta. 손을 같은 무릎에 가볍게 유지하고 contact loss를 감지한다.
- 일반 `leg_press`, `seated_leg_curl`, `leg_extension`, `hip_adduction`, `hip_abduction`: D manual.

## Verification

```bash
JAVA_HOME=/tmp/temurin-17 /tmp/gradle-8.11.1/bin/gradle :core:test --no-daemon
```

계약 테스트는 standard 33행, tier resolve, conditional protocol, D manual fallback, B 제한 출력, legacy form field 비생성 여부를 검증한다.
