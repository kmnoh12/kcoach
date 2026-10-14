# Frozen paper-inspired peak candidate versus current phone engine
616 labelled sets, 21 MM-Fit sessions. Same source gyro times and latest preceding acceleration. No target-count stop or interpolation.
Previously explored public data: this is descriptive replay, not held-out personal/Watch8 acceptance. Known set boundaries bypass session preparation and transport.
Alternating bilateral curls cannot be scored against one wrist without separate arm labels. Manual-only exercises remain unavailable, never zero-error automatic counts.
Candidate uses fourth-order ~2s offline SG, within-unit mean-centered dominant axis, robust range thresholds and complete-return checks. These Watch8 adaptations are not an exact replication of the paper's sensor/equipment thresholds.
Offline lookahead is 1000ms. This configuration cannot satisfy a 500ms watch latency gate; no watch adoption occurs here.
| Exercise | Scored sets | Current raw MAE | Peak raw MAE | Current accepted coverage | Peak accepted coverage | Current accepted exact / all | Peak accepted exact / all |
|---|---:|---:|---:|---:|---:|---:|---:|
| bicep_curls | 0 | N/A | N/A | 0/0 | 0/0 | 0/0 | 0/0 |
| dumbbell_rows | 64 | 2.84 | 1.28 | 47/64 | 64/64 | 0/64 | 8/64 |
| dumbbell_shoulder_press | 60 | 4.50 | 2.30 | 22/60 | 60/60 | 1/60 | 3/60 |
| jumping_jacks | 0 | N/A | N/A | 0/0 | 0/0 | 0/0 | 0/0 |
| lateral_shoulder_raises | 56 | 3.84 | 0.73 | 55/56 | 56/56 | 2/56 | 26/56 |
| lunges | 0 | N/A | N/A | 0/0 | 0/0 | 0/0 | 0/0 |
| pushups | 0 | N/A | N/A | 0/0 | 0/0 | 0/0 | 0/0 |
| situps | 0 | N/A | N/A | 0/0 | 0/0 | 0/0 | 0/0 |
| squats | 0 | N/A | N/A | 0/0 | 0/0 | 0/0 | 0/0 |
| tricep_extensions | 64 | 3.44 | 1.30 | 27/64 | 64/64 | 10/64 | 6/64 |
Missing/rejected predictions stay in coverage and overall exact denominators. Raw MAE must be read alongside coverage. No engine is selected or enabled by this report.
