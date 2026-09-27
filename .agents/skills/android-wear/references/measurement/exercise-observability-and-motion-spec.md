# Exercise Observability and Motion-Spec Rules

Use this reference when implementing or reviewing smartwatch-only exercise repetition tracking, especially for KCoach-style personalized workouts.

## Core product distinction

Separate these claims:

1. **Rep count** — a sensor pattern completed one exercise-specific cycle.
2. **Wrist signal metrics** — wrist angular speed, wrist-motion range proxy, trajectory consistency, tempo consistency, and signal quality.
3. **Exercise/form correctness** — joint alignment, true ROM, chin-over-bar, scapular stability, elbow position, spine position, or load path.

A single wrist IMU may support (1), and sometimes (2), but must not silently imply (3). Use names such as `wristMotionSpeed`, `relativeRomProxy`, and `trajectoryConsistency`; reserve `formScore` for evidence that can observe the required body segments.

## Capability tiers

Classify every exact exercise variant before writing an algorithm:

- **A — wrist-direct auto**: wrist motion is a strong proxy for the exercise; rep count plus selected wrist metrics may be supported.
- **B — auto count, limited metrics**: the wrist is coupled to the implement/body movement; count may be useful, but speed/trajectory are only wrist proxies.
- **C — personalized auto-count beta**: a weak indirect wrist signal exists; support only for a fixed user/setup/style after calibration and expose confidence.
- **D — manual required**: the main movement is not observable from one wrist; keep phone/manual entry unless adding camera, extra IMU, or equipment sensing.

The tier belongs to the exact variant, not only the exercise family. Grip, laterality, bilateral/unilateral execution, watch side, strap position, equipment, and protocol can change observability.

## Required exercise contract

Each exercise profile should declare:

```text
countMode: AUTO | AUTO_LIMITED_METRICS | PERSONALIZED_AUTO_BETA | MANUAL_REQUIRED
signal: FOREARM_ROTATION | ROTATION_ENERGY | DYNAMIC_ACCELERATION | UNOBSERVABLE_FROM_WRIST
supportedMetrics
unsupportedClaims
requiredSetup
fallback
validationGate
```

Do not let a generic acceleration counter fabricate reps for a profile that has not earned a real-device acceptance result.

## Cable external rotation motion spec

Do not confuse this with a palm-up or vertical forearm movement. For the user's left-arm cable external rotation example:

```text
elbow fixed near the torso, elbow about 90 degrees
start: left hand/forearm points forward
action: rotate the forearm outward to the side while keeping the elbow fixed
return: rotate back to the forward baseline
one rep: forward (1) -> side (2) -> forward (1)
```

When the motion is specified by images, describe the frames explicitly and confirm the sequence before implementing. Two images can establish 1 and 2; the return to 1 is an inferred sequence unless a return frame or live capture is present. Do not rewrite the user's motion as a palm-up start if the image shows a forward-facing baseline.

A detector for this exact variant should include, where available:

- left-wrist/laterality profile;
- baseline orientation calibration for the forward-facing start;
- outward direction and return-to-baseline checks;
- stable axis/rotation direction and minimum angular displacement;
- a state machine for `BASELINE -> OUTWARD -> RETURNED`, not merely any gyro sign reversal;
- rejection tests using the user's real vertical-shaking false-positive motion.

A generic dominant-axis gyro detector with sign reversal is only a preliminary forearm-rotation detector. It is not evidence that the left-hand forward-to-side-to-forward exercise is solved.

## Evidence gate

For every claimed fix or accuracy improvement:

1. Unit-test idealized sensor sequences for the intended cycle.
2. Add negative synthetic sequences for acceleration shocks and vertical shaking.
3. Capture labeled real Watch8 IMU data for the exact setup and laterality.
4. Compare against manual or video ground truth, including partial reps, pauses, wrong-direction reps, and nearby false motions.
5. Only then promote the profile from beta/caution to a stronger capability tier.

Build, lint, APK/hash, or OTA success proves delivery, not sensor validity. Emulator/fake telemetry proves state-machine behavior, not real exercise observability.

## Agent instruction template

When delegating implementation, specify the exact motion, capability tier, allowed outputs, forbidden claims, setup, and validation gate. Example:

```text
Exercise: cable_external_rotation_left
Motion: forward baseline -> outward forearm rotation -> forward baseline; elbow fixed, approx 90 degrees
Tier: C personalized auto-count beta
Allowed: rep count, repetition duration, wrist angular speed, signal confidence
Forbidden: scapular stability, elbow alignment, true joint ROM, form correctness
Required: same left wrist, same cable/handle, calibrated baseline
Validation: real Watch8 capture vs manual/video labels; test vertical-shaking negatives


## KCoach 33-exercise motion-family matrix

Use this matrix as an implementation map, not as proof that the current APK already implements or validates every row. The exact catalog is defined in `phone/.../WorkoutData.kt`; do not replace exact IDs with loose `contains("press")`/`contains("fly")` routing when adding detectors.

| # | exercise ID | motion family / count cycle | initial tier | metric boundary |
|---:|---|---|---|---|
| 1 | `pull_up` | hang -> pull -> return to hang | C | count/confidence; no chin-over-bar claim |
| 2 | `assisted_pull_up` | grip baseline -> pull -> return | C | count/confidence; machine/grip-specific |
| 3 | `lying_tbar_row` | reach -> pull to torso -> return | B | count, duration, periodicity; wrist proxy |
| 4 | `lat_pull_down` | arms-up -> pull-down -> return | B | count/duration; no bar travel claim |
| 5 | `seated_row` | arms-forward -> pull-back -> return | B | count/duration; no scapular claim |
| 6 | `ez_biceps_curl` | extended -> flex -> extend | A | count plus wrist angular speed/range |
| 7 | `barbell_biceps_curl` | extended -> flex -> extend | A | same; grip/load calibration |
| 8 | `preacher_curl` | low -> curl -> low | A/B | count/tempo; no elbow-fixation claim |
| 9 | `incline_curl` | extended -> flex -> extend | A | count plus wrist metrics |
| 10 | `incline_hammer_curl` | neutral-grip flex -> extend | A | neutral-grip profile |
| 11 | `dumbbell_pullover` | overhead -> chest-side arc -> overhead | B | count/phase timing; wrist arc proxy |
| 12 | `flat_bench` | top -> lower -> press up | B | count/duration; no chest-touch claim |
| 13 | `incline_bench` | top -> lower -> press up | B | incline-specific baseline |
| 14 | `chest_press` | handle-back -> press-forward -> return | B | count/duration; handle proxy |
| 15 | `machine_fly` | open -> close -> open | B | count/periodicity; no pectoral ROM claim |
| 16 | `cable_pushdown` | top -> push-down -> return | B | count/duration; no elbow-lock claim |
| 17 | `cable_overhead_extension` | flexed -> extend -> flexed | B | count/tempo; wrist proxy |
| 18 | `rope_extension` | top -> rope-down -> return | B | count/duration; one-wrist proxy |
| 19 | `rope_overhead_extension` | flexed-overhead -> extend -> return | B | overhead profile |
| 20 | `dumbbell_triceps_extension` | flexed -> extend -> flexed | B | count/duration; no elbow ROM claim |
| 21 | `ez_lying_extension` | bar-top -> lower-toward-head -> press-up | B | distinct from bench press via phase profile |
| 22 | `external_rotation` | angular excursion -> reversal -> return | C | gyro displacement/speed/confidence; no joint-form claim |
| 23 | `crossover_reverse_fly` | forward/closed -> open-back -> return | B | wrist trajectory proxy |
| 24 | `smith_shoulder_press` | down -> press-up -> down | B | count/duration; bar path proxy |
| 25 | `machine_shoulder_press` | handle-down -> press-up -> return | B | machine-specific baseline |
| 26 | `dumbbell_lateral_raise` | arm-side -> lateral arc up -> arc down | B | relative orientation/arc consistency, not shoulder fixation |
| 27 | `one_arm_cable_lateral` | side baseline -> cable lateral arc -> return | B | unilateral setup-specific profile |
| 28 | `machine_reverse_fly` | forward/closed -> open-back -> return | B | do not collapse into generic fly profile |
| 29 | `leg_press` | leg movement not represented at wrist | D | manual; optional explicit hand-on-knee proxy is a separate variant |
| 30 | `seated_leg_curl` | lower-leg movement not represented at wrist | D | manual |
| 31 | `leg_extension` | lower-leg extension not represented at wrist | D | manual |
| 32 | `hip_adduction` | thigh adduction not represented at wrist | D | manual |
| 33 | `hip_abduction` | thigh abduction not represented at wrist | D | manual |

For the upper-body rows, implement family detectors plus exact profiles: `ROTATION_RETURN`, `FLEXION_EXTENSION`, `PRESS_RETURN`, `PULL_RETURN`, `RAISE_ARC`, `FLY_OPEN_CLOSE`, and `PULLOVER_ARC`. A family detector is not enough by itself: each profile must set the expected phase order, excursion range, duration/separation bounds, and negative/null motions. For example, a lateral raise must use a relative-orientation/arc-return state machine; a generic dynamic-acceleration burst is not an adequate implementation of the exercise.

Promotion order should be conservative: first run deterministic replay tests for all 33 profile resolutions and representative positive/negative signals, then validate the highest-observability wrist-direct exercises on real Watch8 captures, then B exercises, then C exercises. Keep the five lower-body machine rows manual until an additional sensor/camera/equipment signal exists. Never promote all rows merely because the shared detector or build passes.
