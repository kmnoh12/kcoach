# 워치짐 Watch8 measurement audit

Use this reference when a user reports unstable real-device counting or asks whether calibration/form/trajectory metrics are real.

## Audit order

1. Read the current detector and the watch service call site before proposing a threshold change.
2. Trace `START → PREPARING → beginCounting → detector.add → rep event → set completion → capture → phone analysis → UI`.
3. Mark whether the start gate is a fixed timer or a continuous sensor condition. A fixed delay is not posture recognition.
4. Trace every calibration field from raw capture through persistence, watch-side profile construction, phone reanalysis, and displayed result. Mark each field `used-for-count`, `used-for-tempo`, `display-only`, or `unused`.
5. Search for the actual consumer of form/trajectory classes. A class or data field that is never called is not an active product feature.
6. Check wire types at both send and receive sites; an Intent/Bundle type mismatch can make one metric disappear without stopping the workout.
7. Separate stored raw capture, synthetic tests, emulator fake telemetry, Data Layer delivery, and Watch8 sensor accuracy.

## Honest Watch-only contract

| Claim | Watch-only status |
|---|---|
| Repetition count | Valid candidate for wrist-direct motion after Watch8 labeled validation |
| Average repetition duration | Valid from accepted rep boundaries |
| Fast/slow versus personal reference | Valid as relative tempo, not a form verdict |
| Wrist angular speed/range | Valid as wrist-observed proxy |
| Wrist stability/variability | Possible proxy if defined and validated |
| True 3D hand path | Not reliable from raw IMU integration alone |
| Centimeter load/bar travel | Not available without equipment/scale calibration and validation |
| Elbow/palm/body posture | Not directly observable from one wrist |
| Full-body form quality | Requires camera, multiple sensors, equipment sensors, or manual labels |

For cable external rotation, a defensible preparation gate is `quiet gyro + stable gravity/orientation for 2 continuous seconds`, with the timer reset by motion. It does not verify elbow 90 degrees, palm-facing direction, or elbow fixation; name the output `wrist setup stable`.

## Simplification rule

Use shared motion families (`PRESS_RETURN`, `PULL_RETURN`, `FLEXION_EXTENSION`, `FOREARM_ROTATION`, `MANUAL_REQUIRED`) with small profile metadata. Flat and incline bench may share the same press detector. Do not force every exercise into a unique algorithm or a trajectory/form score. For unsupported exercises, use manual input instead of a confident fallback.

## Tempo rule

Store a personal reference from a valid calibration set. For a reference of 10 reps in 40 seconds, a first-pass product rule can emit one end-of-set haptic and a neutral warning at 35 seconds or faster. Prefer comparing average accepted-rep duration when the set has a different rep target. Keep fast-tempo and late-set slowdown as separate findings. Never convert “faster” into “bad form” without form labels.

## Evidence ledger

Record claims separately:

```text
source/profile changed
fresh core tests passed
APK assembled
APK installed on target
watch service received real sensor events
raw Watch8 capture persisted
phone reanalysis completed
manual/video count comparison completed
accuracy result by exercise/condition
```

Synthetic detector tests prove only algorithm mechanics on synthetic samples. Emulator fake reps prove only state/transport behavior. A suspicious log pattern such as `sensorEvents=0` alongside nonzero fake reps or a tiny synthetic capture must not be reported as physical sensing evidence.

## Report template

```text
exercise / variant / protocol:
watch side + wear position + grip/contact:
start gate: fixed delay | continuous wrist-stability gate | manual
watch events: real sensor count / fake driver count / unknown
watch reps / phone reps / manual-video reps:
false positives / missed reps / ±1 result:
mean rep duration + reference comparison:
proxy metrics shown:
forbidden claims suppressed:
calibration fields actually consumed:
validation status: UNVALIDATED | EMULATOR_ONLY | WATCH8_SIGNAL_VALIDATED | WATCH8_ACCURACY_VALIDATED
next smallest experiment:
```
