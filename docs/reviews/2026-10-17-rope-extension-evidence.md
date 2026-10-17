# Rope extension evidence 9cf833a3

## Scope

One user-supplied paired capture, exercise `rope_extension`, left wrist, outside
placement. User review records 10 repetitions (`USER_REVIEWED_VIDEO`). It does
not provide reviewed per-repetition boundaries. Original video and sensor files
remain in ignored `work/evidence/9cf833a3/`; they are not committed.

## Integrity and sensor observations

All nine archive entries passed indexed size/hash checks. Raw sensor SHA-256:
`4758f03f69044b149d71e62dea334e3f69f9817a7a7e6cb370408e60cc614380`.
There are 1,624 samples over 32.496 seconds, observed 49.945 Hz, with a longest
gap of 22.128 ms. Both sensor accuracy fields are HIGH for every sample; no
missing original clocks, stale acceleration joins, or unreliable samples were
observed. These are capture metadata checks, not proof of counting accuracy.
The export includes 257 camera records and the reference video.

## Detector-only replay

| Detector | Count | End times in sensor seconds |
|---|---:|---|
| Captured frozen press-return-family-v4 | 11 | 6.94, 9.70, 12.39, 16.17, 20.06, 22.48, 24.82, 27.27, 29.67, 30.75, 32.34 |
| Current rope profile | 11 | Same as frozen |
| Existing ReturnToPoseRepDetector, unadopted comparison | 10 | 6.44, 9.20, 11.91, 14.57, 17.13, 19.68, 22.08, 24.54, 26.95, 29.41 |

Replay uses the original samples and sensor-quality filtering, with no target
count, camera input, future samples, or synthetic final repetition. Runtime
preparation state is outside this comparison. The shared vertical-press change
is enabled only for dumbbell shoulder press; it does not alter this exercise.

Visual inspection places rope release and approaching the phone around the last
two frozen detections. Their timing is suspicious for handling false positives.
Counts and timing also differ in the middle, so subtracting one final repetition
would not establish correct repetition detection. Clock estimates vary by about
586 ms across samples; exact camera/sensor boundary matching is unverified.

Exploratory camera elbow-angle thresholding produces 10 cycles ending around
6.40, 9.17, 12.01, 14.58, 17.35, 19.88, 22.28, 24.78, 27.15, and 29.62 video
seconds. This is an analysis script, not the application's camera tracker or
independent reviewed ground truth. The first visible movement's inclusion in
the user's count remains to be clarified. A matching aggregate count does not
validate the pose candidate or justify routing all users to it.

## Tool correction and checks

The archive inspector now accepts the optional `performance.json` emitted by
the current app while retaining size/hash checks and unknown-entry rejection.
Three selected real-export compatibility tests passed: original acceptance,
modified performance bytes rejection, and unknown-entry rejection.
`SelectedEvidenceReplayTest` passed on this ZIP. These checks were run narrowly;
no full suite, app release, push, or physical-device validation was performed.

Reproduce the selected checks by setting `KCOACH_EVIDENCE_ZIP` to the original
ZIP, then running Python unittest class
`test_inspect_measurement_export.CurrentExportCompatibilityTest` with
`PYTHONPATH=tools`, and the core test `*SelectedEvidenceReplayTest`.

No production detector, application version, or deployment was changed.
