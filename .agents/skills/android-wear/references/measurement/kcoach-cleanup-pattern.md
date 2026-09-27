# KCoach wearable-measurement cleanup pattern

Use this reference when simplifying a watch-only exercise app without deleting the measurements that are actually observable.

## 1. Trace before editing

Map each output from:

```text
watch sensor callback
→ detector/analyzer
→ result model
→ Data Layer / Intent / persisted payload
→ phone listener
→ UI row or coaching rule
```

Classify every output as:

- retained: rep count, wrist-observed amplitude/range proxy, rep/set duration, tempo comparison, signal quality;
- beta: personalized threshold/axis adaptation and exercises with indirect wrist observability;
- removed: synthetic reps/heart rate/samples, whole-body form, elbow/palm confirmation, spatial trajectory, body sway, joint ROM, equipment travel distance.

A calculation is not harmless because its result is nullable. Remove its production call path, model field, serialization, UI, capability declaration, and metric-specific tests. Keep only a narrow legacy decoder if old saved sessions need to load.

## 2. Start gate

Do not use a blind `postDelayed(2_000)` as posture recognition. Use one shared gate with small per-family thresholds:

```text
PREPARING
  → candidate: plausible gravity magnitude + low gyro activity
  → stable: orientation drift remains within tolerance
  → READY: stable continuously for 2 seconds
  → COUNTING
```

Reset the hold timer on motion, orientation drift, invalid gravity magnitude, or a long sensor gap. Call `detector.beginCounting()` only on the transition to `READY`. For external rotation, label the cue as a wrist-reference setup; do not claim that one watch proves palm direction, elbow angle, or elbow fixation.

## 3. Shared detector families

Use a shared detector implementation and stable family IDs. Example families:

- `FLEXION_EXTENSION`
- `PRESS_RETURN` (flat bench, incline bench, chest press, similar press/return cycles)
- `PULL_RETURN`
- `MULTI_AXIS_RETURN` (fly/arc/pullover-like wrist cycles)
- `FOREARM_ROTATION`
- `MANUAL_REQUIRED`

Exercise rows may retain threshold differences, but similar rows should resolve to the same family algorithm ID. Add a mechanical test asserting that flat and incline bench resolve to the same `PRESS_RETURN` algorithm.

## 4. Synthetic telemetry rule

Delete emulator-only fake repetition tickers and fake heart-rate/sample generators from production runtime. Keep emulator transport/lifecycle plumbing only when it does not fabricate a result. A passing emulator session with `sensorEvents=0` and nonzero reps is synthetic evidence, not sensor validation.

For the KCoach service pattern, audit the whole emitter path rather than deleting a shared rep function by name:

- `WearWorkoutService.startFakeRepTicker()` is the synthetic emitter; remove its callers from session start/resume and calibration/measurement preparation paths.
- Remove synthetic HR initialization and rest-time decay separately; they are easy to miss after the rep ticker is gone.
- Preserve `recordDetectedRep()` when the real sensor detector calls it. It is a shared sink, not itself fake telemetry.
- Keep emulator relay/listener and lifecycle plumbing only if it carries commands/state without manufacturing reps, HR, or sensor samples.

After editing, search production source for the generator, its stop/cleanup fields, all call sites, and the synthetic constants. A single remaining scheduled caller can keep fabricating results even when the ticker declaration is gone.

## 4a. Residual-path audit after a partial patch

An import, enum change, or new gate class does not prove runtime integration. Re-open the actual service state machine and verify:

```text
START/RESUME → PREPARING
sensor callback → candidate/stability observation
continuous stable hold → measurementReady/READY
READY transition → detector.beginCounting()
STOP/PAUSE/timeout → gate reset + sensor/timer cleanup
```

Search for the old fixed preparation scheduler and inspect every transition into `measurementReady`; do not accept “the gate is imported” as evidence. Do the same for removed result metrics: inspect model, analyzer, wire/Intent, persistence decoder, UI, capability metadata, and tests, then run a repository-wide term sweep. Keep old persisted data readable only through a narrow decoder and never re-emit removed metrics.

## 5. Resource accounting

Deleting fake analytics reduces CPU/event/payload/UI overhead. It does not automatically make raw sensor capture cheap. For a major battery/storage claim, separately record:

- registered sensor types and requested delay;
- capture sampling interval and samples per set;
- chunk/persistence size and upload count;
- phone reanalysis requirements;
- Watch8 set duration and battery delta.

Do not claim large battery savings from removing nullable result fields alone. If capture is reduced, replay retained rep/range/tempo tests against real Watch8 captures before shipping.

## 6. Verification gate

Before reporting completion, verify in this order:

1. no removed metric names or fake generators remain in production Kotlin paths;
2. core compile/tests pass, including gate reset/2-second and shared-family tests;
3. phone and wear debug artifacts build from the current tree;
4. emulator/lifecycle tests still pass without fabricated reps;
5. a physical Watch8 capture proves the start gate and retained metrics;
6. UI/wire types match at both ends; never read an integer extra as a `Double`;
7. Obsidian records removed claims, retained claims, validation status, and the exact resource claim supported by evidence.

Source edits, built artifacts, installed versions, Data Layer delivery, and physical accuracy are separate claims. Report each separately.