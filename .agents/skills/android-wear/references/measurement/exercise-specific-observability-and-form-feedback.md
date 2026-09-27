# Exercise-specific observability and form feedback

Use this reference when a phone↔Wear workout system claims exercise-specific rep counting, trajectory analysis, speed, sway, or automatic form correction.

## Evidence boundary

A wrist IMU observes wrist motion, not the whole body. Keep these claims separate:

- **wrist-motion metric**: computed from acceleration/gyroscope samples;
- **joint/whole-body posture**: requires a biomechanical model, camera pose, or additional body/machine sensors;
- **transport/lifecycle evidence**: proves commands, state, capture, and cleanup, not exercise classification accuracy.

A profile being selected by `exerciseId` is not proof that it is optimized. Optimization requires labeled real-watch or replay data and per-exercise metrics.

## Conservative profile matrix

| Exercise family | Wrist-only policy | Required profile evidence |
|---|---|---|
| Pull-up / assisted pull-up | Beta/caution | calibration, hang/ascent/top/descent phases, ROM gate, kipping rejection, grip/orientation variants |
| Lat pulldown / row | Beta/caution | pull direction, pause/turnaround, handle/grip variants, tempo separation |
| Curl / extension | Caution | rotation axis calibration, minimum ROM, rep separation, load/tempo variants |
| Press / raise / fly | Caution | dynamic acceleration signal, orientation variants, short-ROM rejection, sway/jerk limits |
| Machine leg press / leg curl / extension | Manual required with wrist-only input | a wrist signal must not be treated as a leg repetition; add camera, leg/machine sensor, or manual confirmation before automatic mode |

## Profile contract

Each profile should define, in shared code or an equivalent versioned artifact:

```text
profileId / algorithmVersion
trackingMode: WRIST_IMU | WRIST_IMU_CAUTION | MANUAL_REQUIRED
signal and coordinate/calibration assumptions
minimum and maximum repetition duration
minimum separation / hysteresis
minimum usable ROM or energy
confidence and rejection policy
form metric semantics and user-facing labels
```

Unknown or unsupported exercises should resolve to a cautious profile or manual mode. They should not silently inherit a universal threshold and present a confident count.

## Replay and real-device acceptance

For each automatically counted profile, keep deterministic fixtures containing:

- rest/noise segments;
- clean repetitions at slow, normal, and fast tempo;
- partial ROM;
- pauses at bottom/top;
- double-peaks and rebound/kipping;
- orientation/grip changes;
- sensor dropout, timestamp jitter, and low accuracy;
- at least one negative example that looks active but is not a repetition.

Report at least:

```text
true repetitions
accepted repetitions
missed repetitions
false positives
rejected partial/rebound repetitions
precision / recall
per-set confidence distribution
```

A passing unit test on synthetic pulses proves the detector implementation, not Watch8 exercise accuracy. Require a labeled physical-watch acceptance sample before changing a profile from caution to automatic.

## Metric labels and form feedback

The phone result should expose the source and semantic limits:

- `angular speed`: peak/average wrist angular speed, not barbell or joint speed;
- `relative ROM`: normalized wrist-signal amplitude within the set, not joint range of motion;
- `sway`: wrist acceleration/trajectory stability score, not torso sway unless the sensor is on the torso;
- `form score`: deterministic sensor-rule score with profile and confidence;
- `corrections`: actionable suggestions such as reduce rebound, slow the eccentric phase, or make the start/end position clearer.

Never display a wrist-derived score as if it were camera pose or a medical/biomechanical assessment. For `MANUAL_REQUIRED`, show the reason and preserve the manual result; do not display zero as a confident automatic count.

“Automatic form correction” in this class of system normally means automatic feedback/rule selection. It does not mean the app physically corrects the user's movement. If camera pose or a learned model is later added, version and validate that model separately.

## UI and persistence gate

Carry these fields from phone capture analysis through the broadcast, result DTO, persistence, and result screen:

```text
exerciseId, exerciseName, setIndex
watch provisional reps, phone reps, confirmed reps
trackingMode, trackingProfileId, trackingStatus
confidence, angular speed, relative ROM, sway score
form score, form findings/corrections
```

Verify each lane independently. A field present in `SetAnalysisResult` but absent from the Intent or result card is not a delivered metric. A result card showing a metric is not proof that the underlying capture was hash-verified unless the UI labels the capture/analysis state accordingly.

## Completion rule

Do not mark exercise-specific tracking complete until:

1. the profile is selected by exercise identity;
2. unsupported motions are explicitly manual or separately instrumented;
3. replay tests cover positives and negatives;
4. real Watch8 captures are labeled and evaluated;
5. the phone result shows source, confidence, metric semantics, and corrections;
6. capture/ACK and lifecycle cleanup are separately verified.
