# Profile-driven exercise detector pattern

Use this reference when refactoring a catalog of wrist-worn exercise detectors without pretending that a wrist IMU measures whole-body form.

## Contract shape

Represent each exact catalog exercise with an explicit profile keyed by `exerciseId`, not by a substring of the algorithm label. Keep these fields together:

- motion family: flexion/extension, press-return, pull-return, arc trajectory, fly open/close, pullover arc, rotation-return, or manual;
- primary signal: signed gyro, gravity-compensated dynamic acceleration, or a bounded gyro/acceleration composite;
- minimum feature rate, lobe displacement, lobe/rep duration, inter-rep separation, and optional multi-axis trajectory spread;
- capability tier (`A`, `B`, `C`, `D`), count mode, user-facing metrics, unsupported metrics, and validation status.

A profile's algorithm ID may be human-readable and versioned (`...-v1`), but the map must retain an explicit `exerciseId` field. Do not reconstruct the catalog key by stripping suffixes or replacing punctuation in an algorithm ID; that silently breaks names such as `pull_up` versus `pull-up-pull-return-v1`.

## Deterministic cycle engine

A shared detector can serve several families while profiles supply the thresholds and phase semantics. Count only after two valid opposite-direction lobes complete an out-and-back cycle:

```text
READY -> OUTGOING -> TURNAROUND -> RETURNING -> BASELINE -> COMMIT -> REFRACTORY
```

Minimum implementation gates:

1. lock a dominant signal axis after the first usable feature;
2. integrate signed feature magnitude over time for each lobe;
3. reject lobes below minimum rate, displacement, or duration;
4. require the full rep duration and minimum separation;
5. enforce a refractory interval after a committed rep;
6. flush the final lobe at set end so a completed return is not lost;
7. reset on timestamp reversal or a long sample gap.

For `ARC_TRAJECTORY`/fly/pullover profiles, multi-axis secondary energy may be required as a trajectory-spread proxy. Label this as wrist trajectory consistency, not anatomical shoulder arc or strict form. A spread gate is not proof of elbow/shoulder position.

For rotation-dominant profiles such as cable external rotation, use the dedicated signed gyro-rotation detector rather than a generic acceleration magnitude threshold. For press/pull/arc families, if dynamic acceleration is used, estimate and subtract a low-pass gravity vector, then keep shock rejection through minimum lobe duration and displacement gates.

## Phone/Wear routing

The phone's post-set analyzer and the Wear live service must resolve the same profile and call the same family detector. Do not leave a generic live counter on Wear while Phone reanalysis uses a profile-specific algorithm. Keep the existing forearm-rotation detector as a distinct path if its event semantics differ, but make its profile/capability contract explicit too.

Unknown IDs and unobservable lower-body exercises must resolve to `MANUAL_REQUIRED`; never let an unknown exercise fall through to a broad `curl`/`press`/`raise` substring detector.

## Evidence and test gates

Maintain separate labels for implementation and truth:

- source/profile contract exists;
- family replay passes;
- phone reanalysis passes;
- Wear live route compiles;
- app builds/lint passes;
- emulator transport/state passes;
- real Watch raw-IMU validation passes.

At minimum, add tests for:

- every catalog ID resolving to an explicit profile;
- all lower-body/manual IDs remaining manual;
- clean slow/normal/fast out-and-back cycles;
- partial/one-way cycles, short impacts, vertical shaking, pauses, timestamp faults, and dropout gaps;
- one end-to-end phone analysis for a representative pull/press/flexion/arc family;
- the dedicated rotation detector's direction reversal and acceleration-only negative case.

Synthetic replay proves detector implementation, not Watch8 accuracy. Keep non-manual profiles in a caution/beta validation state until labeled Watch8 raw IMU is compared with manual or video ground truth. Do not promote A/B/C based only on a green Gradle build, emulator fake telemetry, or a connected ADB device.

## Current KCoach application pattern

The catalog currently uses 33 exact profiles: 28 wrist-IMU caution/beta profiles and 5 lower-body manual profiles. The observable metrics are wrist proxies (`rep_count`, `rep_duration`, relative wrist motion range, trajectory/tempo consistency, and signal quality). Whole-body form, joint ROM, scapular alignment, strictness, chin-over-bar, and actual load travel stay unsupported until another sensor or labeled validation protocol exists.
