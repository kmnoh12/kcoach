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

## Second capture cfc9dc8b

The next independently supplied capture is also `rope_extension`, left wrist,
outside placement, reviewed as 10 repetitions without reviewed boundaries.
All nine indexed entries passed integrity checks. Raw SHA-256:
`2e8573e30bbef1092021d1462ac5ebed9bb5b39806ca0f3ef9a59c4a6b8f4f79`.
The 1,566 samples span 31.351 seconds at 49.919 Hz, longest gap 20.326 ms.
Both sensors report HIGH accuracy throughout, with no missing clocks, stale
acceleration joins, or unreliable samples. Camera records total 248.

The same selected replay test passed with this original ZIP:

| Detector | Count | End times in sensor seconds |
|---|---:|---|
| Frozen and current rope profile | 6 | 6.49, 10.13, 14.88, 21.15, 23.74, 27.26 |
| Unadopted ReturnToPose comparison | 10 | 6.13, 8.67, 11.13, 13.62, 15.86, 18.29, 20.77, 23.28, 25.72, 28.25 |

Across these two user-reviewed 10-rep captures, the existing detector produces
11 and 6, whereas the unchanged comparison detector produces 10 and 10.
The second capture therefore exposes undercounting, not just the first
capture's net overcount. A constant final-count correction cannot solve both.
Aggregate absolute count error averages 2.5 for the existing detector and 0 for
the comparison on these two explored captures only. This is not population
accuracy or independent validation, and does not establish per-rep correctness.

Sparse video contact sheets confirm rope pushdowns followed by approaching the
phone; they are insufficient for manually labeling every repetition. Exploratory
camera thresholding again totals 10 but ends its last cycle around 31.26 video
seconds, after the pose comparison's last event around 28.25 sensor seconds.
It also lacks a cycle around the comparison's 15.86-second event. Thus even a
camera total of 10 must not be mistaken for a matching set of rep boundaries.
Clock-offset estimates span about 575 ms. Candidate adoption remains pending
boundary review and broader exercise-specific checks.

Original extracted files and replay CSV are retained privately under ignored
`work/evidence/cfc9dc8b/`. Only this analysis note is added to version control;
the production engine, versions, and releases are unchanged.

## Third capture 9fb34269

The third supplied capture is again `rope_extension`, left wrist, outside,
with a user-reviewed total of 10 and no reviewed repetition boundaries.
All nine indexed archive entries pass integrity checks. Raw SHA-256:
`82dbf1ff759fe2c23952b57e139a76d97eb195ecf1d397f88417cfd01ab6ea6f`.
There are 1,534 samples over 30.706 seconds at 49.925 Hz, longest gap 20.315 ms.
Both sensor accuracy fields are HIGH throughout; no unreliable samples,
missing original clocks, or stale acceleration joins are reported. The export
includes 238 camera records and a video; no video boundary review was performed
for this third-capture comparison.

The selected original-evidence replay test passed. Frozen and current profiles
both produce 7, matching the captured watch count, with sensor end times
7.93, 12.73, 17.54, 21.55, 23.93, 26.46, and 28.82 seconds. The same unchanged,
unadopted ReturnToPose detector produces 10, with end times 6.45, 8.97, 11.29,
13.76, 16.12, 18.64, 21.15, 23.49, 26.10, and 28.60 seconds. This remains a
detector-only replay without target count or camera input.

| Capture | Reviewed total | Frozen/current | Unadopted pose comparison |
|---|---:|---:|---:|
| 9cf833a3 | 10 | 11 | 10 |
| cfc9dc8b | 10 | 6 | 10 |
| 9fb34269 | 10 | 7 | 10 |

These three explored captures strengthen the case for evaluating the existing
pose detector for this exercise. Matching totals in three captures from one
user do not establish boundary accuracy, other-user performance, or release
readiness. No detector settings were tuned between these three comparisons.
The third capture's original files, integrity report, and replay CSV are retained
under ignored `work/evidence/9fb34269/`. Production routing and versions remain
unchanged; this is a local analysis-only commit without push or deployment.

## Adopted in source after user authorization

`rope_extension` now resolves to the existing FLEXION_EXTENSION pose-return
family (`flexion-extension-pose-v4`), already used for preacher curls. No other
exercise binding or detector thresholds change. This is a shared exercise
default, not a user-specific fitted model. The family supplies a 350 ms minimum
rep duration; the earlier direct comparison used the old press profile's 400 ms
minimum. Both produce the same reviewed totals on these captures.

Frozen rope press-return v3/v4 snapshots still decode and replay the original
combined-signal algorithm. The exception is restricted to the migrated rope and
dumbbell-shoulder-press exercise identities, rather than accepting historical
press routing for every flexion exercise. Existing captured counts are not
rewritten by this source change.

Verification on the changed source:

- Added routing/snapshot test first; it failed on the old rope binding, then
  passed after the migration. Other press bindings and rejection of an
  incompatible bench snapshot are covered in the same narrow test class.
- `RopePoseEvidenceTest` passed over the three full raw captures, retaining
  historical counts 11/6/7 and asserting new counts 10/10/10 without target or
  camera input. Set-end `finish()` creates no extra repetition. Private captures
  are optional external test inputs, not embedded fixtures.
- `ProfiledMotionRepDetectorTest` passed, including partial-return, unreliable
  input, and non-cycle rejection cases. No full suite or Android package build
  was run.

Video review was extended from sparse second-capture frames and an unreviewed
third video to all eight dense contact sheets in each of those captures (70
selected frames each). The first capture's nine contact sheets had already been
inspected. Rope release/phone approach is distinct from the working cycles;
the new detector's final events occur before those handling tails, with no
subsequent committed repetitions in the full sensor replay. Initial movement
classification remains based on the user's reviewed total, not a new manual
boundary annotation.

An exploratory elbow-angle check using departure at 140 degrees and return at
100 degrees yields 10 working cycles in each capture. In the second capture it
also finds an extra cycle at approximately 31.26 video seconds during handling;
the watch pose detector does not count that event. At a 150-degree departure,
the camera check misses one actual working excursion in the second and third
captures, so this camera threshold is neither ground truth nor a production
change. Its working-cycle sequence supports coarse correspondence with the
watch detections, not frame-accurate labels given clock uncertainty.

Source and targeted replay checks are complete. A newly installed phone/watch
version and live workout behavior have not been verified. No version bump,
push, APK release, deployment, or device installation is part of this change.
