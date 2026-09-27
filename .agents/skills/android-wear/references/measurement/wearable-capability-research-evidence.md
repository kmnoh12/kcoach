# Wearable exercise capability research evidence bank

This is a compact evidence ledger for future smartwatch exercise sensing research. It records source-reported facts separately from KCoach inference.

## Academic and public-data sources

### RecoFit / Microsoft dataset

- Paper: Morris, Saponas, Guillory, Kelner, “RecoFit: using a wearable sensor to find, recognize, and count repetitive exercises,” CHI 2014. DOI: https://doi.org/10.1145/2556288.2557116
- Dataset/code: https://github.com/microsoft/Exercise-Recognition-from-Wearable-Sensors
- Direct README/data documentation states: accelerometer and gyroscope recordings from 200+ participants; single-activity and multi-activity files; 50 Hz examples; multi-activity contains exercise/non-exercise traces and exercise start/end plus repetition-count metadata.
- Additional protocol detail from the paper-side research: the wrist watch was kept in a fixed orientation and tightened to limit rotation; a vibration cue at repetition start was paced per participant and exercise to approximate natural cadence; a null/non-exercise class covered sitting, standing, walking, and equipment transitions. The study used constrained labeling/temporal cues, so its accuracy is not universal-form evidence.
- KCoach inference: recognition, repetition counting, and set segmentation are separate evaluation problems; retain all three labels when collecting Watch8 data.

### WeakCounterF

- Nishino, Maekawa, Hara, “Few-Shot and Weakly Supervised Repetition Counting With Body-Worn Accelerometers,” Frontiers in Computer Science 2022. DOI: https://doi.org/10.3389/fcomp.2022.925108
- Reported setup: ASUS ZenWatch on the right wrist, 100 Hz 3-axis acceleration, 9 subjects, push-ups/squats/sit-ups. Target-user calibration used only weak count labels for a few segments; the proposed few-shot method improved reported MAE over a non-personalized baseline. Reported average MAE was about 1.02 reps/set for the proposed method and 1.09 for one-shot in the tested offline protocol.
- Explicit limitation: experiments were offline; real-time validation was future work.
- KCoach inference: a 5–10 repetition personal calibration can be a candidate for cadence/template adaptation, but not a license to claim anatomical form or hidden joint landmarks.

### Complex exercise counting

- “Recognition and Repetition Counting for Complex Physical Exercises with Deep Learning,” Sensors 2019. DOI: https://doi.org/10.3390/s19030714
- Extracted paper experiment: 54 participants; smartwatch + additional sensor setup; constrained and unconstrained exercise conditions; the paper reports roughly 91% of sets within ±1 rep in its constrained study and emphasizes harder transitions/fast repetitions in less constrained conditions.
- KCoach inference: a good reported set-level count result does not transfer automatically to Watch8, a different sensor placement, or all exercises; keep participant/session and protocol splits.

### Local muscular endurance / upper-vs-lower body

- Prabhu et al., “Recognition and Repetition Counting for Local Muscular Endurance Exercises in Exercise-Based Rehabilitation,” Sensors 2020. DOI: https://doi.org/10.3390/s20174791
- Reported setup: 76 participants, wrist-worn Shimmer3, 10 exercises, including upper- and lower-body movements. The paper compares statistical peak/feature methods with neural models and reports that statistical performance is stronger for upper-body than lower-body, while neural models can improve lower-body counting under the study protocol.
- KCoach inference: lower-body machine exercises should remain manual by default unless a new protocol creates an observable signal and is validated on Watch8.

### Few-shot unseen exercises

- Lim and Lee, “Intelligent Repetition Counting for Unseen Exercises: A Few-Shot Learning Approach with Sensor Signals.”
- Reported setup: 11 participants; one IMU above the ear; 92 Hz accelerometer/gyro/magnetometer; 28 selected exercises from 30 because two lacked clear sensor patterns; 5-repetition registration for new exercise adaptation; reported 86.8% probability of accurately counting 10+ reps in a set across the selected exercise set.
- KCoach inference: the method supports the concept of personal registration, but the sensor position is head/ear rather than wrist and cannot be used as Watch8 accuracy evidence. Exercise-specific window/hyperparameter sensitivity remains relevant.

### Wearable IMU paper patterns

Across the above studies and their cited prior work:

- wrist/forearm-coupled upper-body movements tend to expose more useful repetition signal than quiet-grip lower-body machine movements;
- multiple peaks per repetition, pauses, tempo change, fatigue, sensor pose, and user morphology create count errors;
- frequency/periodicity is useful as a prior but does not by itself prove a valid anatomical repetition;
- a study using chest, head, ankle, or multiple sensors is not a wrist-only result.

## Open-source implementation evidence

### `gcornella/Movement-Repetition-Counting-for-Wearable-IMUs`

https://github.com/gcornella/Movement-Repetition-Counting-for-Wearable-IMUs

Verified code patterns:

- FitCoach-style path: acceleration magnitude → gravity estimate (low-pass) → linear-acceleration magnitude → low-pass → short-time energy → adaptive peak/prominence + minimum distance → valley boundaries.
- Džaja/Shimmer-style path: vector magnitude → 0.25–1.2 Hz band-pass → dominant repetition frequency via FFT → period-aware low-pass/extrema selection, with half-period double-count protection.
- KCoach inference: transfer the signal-processing patterns only after measuring Watch8 effective rate, latency, and exercise-specific period; do not copy offline `filtfilt` or fixed thresholds into live watch logic.

### `Yasmine56/crossfit-imu-motion-analysis`

https://github.com/Yasmine56/crossfit-imu-motion-analysis

Verified code pattern: acceleration norm → rolling average/downsample → PELT change-point segmentation. KCoach inference: active/rest segmentation is a separate layer from repetition count and may be useful for set boundaries, but the repository itself is not Watch8 validation.

## Official platform evidence

- Android sensor overview: https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview
- SensorEvent: https://developer.android.com/reference/android/hardware/SensorEvent
- Wear OS Health Services: https://developer.android.com/health-and-fitness/health-services

Official docs establish that accelerometer, gyroscope, gravity/linear-acceleration, and rotation-vector streams have different semantics and that devices differ in available sensors, range, resolution, and effective sampling. Health Services provides platform fitness data and exercise lifecycle APIs; it does not establish a universal custom strength-rep/form classifier. Requested sensor delay is not a guarantee of effective sampling; store event timestamps and count gaps.

## Protocol candidates for KCoach

### Pull-up

Candidate: `C / personalized_auto_count_beta` only under same bar, grip, watch side, strap tightness, and a labeled 5–10 rep calibration. Use cadence, phase timing, wrist template similarity, active/rest transition, and signal quality. Rest posture is a set-boundary cue, not proof of a completed rep. Do not claim chin-over-bar, strict ROM, scapular control, elbow position, or kipping detection from one wrist. Keep explicit manual end and correction.

### Leg press

- standard hands-free: `D / manual_required`;
- hand-on-knee protocol: conditional `C / proxy beta` only while contact/setup remains stable.

On contact loss, grip/setup drift, or template mismatch, downgrade confidence and fall back to phone manual input. Store the protocol name in the result so the proxy cannot be mistaken for standard leg-press capability.

### Leg curl, leg extension, hip adduction/abduction

Default `D / manual_required`. Watch may supply session time, HR, and activity/rest context; no automatic lower-body repetition or form score without a separately validated setup-assisted mode.

## Evidence discipline when sources are blocked

During the source pass, Reddit search/API and some Chinese search endpoints returned anti-bot or empty results, while accessible public GitHub, DOI metadata, papers, CSDN result pages, and official Android docs were available. The durable rule is:

- use community sources for failure modes and practical ideas only after reading the actual post;
- if only a title/snippet is visible, mark it `discovery_only`;
- do not state that Reddit or a Chinese developer community confirmed a claim when the post body was not retrieved;
- prefer direct repository files, DOI/PMC/OpenAlex/Crossref metadata, official docs, or a transparent “not verified” label.

## Protocol-level practitioner corroboration

- Gymatic public App Store disclosure: https://apps.apple.com/us/app/gymatic-workout-tracker/id1036069872. It describes a leg-press workaround in which the phone is strapped to the leg or the hands are kept on the leg so the sensor follows leg motion, and recommends a snug watch position above the wrist bone. Treat this as a vendor protocol claim, not independent accuracy evidence; it supports modeling hands-free leg press and hand-on-leg proxy as separate variants.
- EvilIrving/swift-fitness Apple Watch prototype: https://github.com/EvilIrving/swift-fitness and its Chinese calibration checklist https://github.com/EvilIrving/swift-fitness/blob/swift-fitness/%E6%89%8B%E8%87%82%E5%8A%A8%E4%BD%9C_%E4%B8%BB%E7%89%B9%E5%BE%81%E4%B8%8E%E6%A0%A1%E5%87%86%E6%B5%8B%E8%AF%95%E6%B8%85%E5%8D%95.md. The prototype proposes 2 s stillness → 5–10 reps → 2 s stillness, `zoneA/zoneB`, minimum stroke, interval and duration limits, watch side, gravity projection plus gyro reversal, and explicit tests for slow/normal/fast cadence, pauses, grip changes, and interference. It is reproducible design evidence but not independently validated field accuracy.

These sources reinforce a general rule: fixed watch placement, contact/grip protocol, per-exercise cadence envelopes, A→B→A phase closure, and explicit invalidation/fallback should be stored as measurement provenance rather than hidden in generic thresholds.

## Validation gate

For each exercise/protocol, compare Watch8 raw IMU/HR to video or per-rep manual labels. Stratify by watch side, strap tightness, grip, load, tempo, fatigue, partial reps, rest gestures, and transitions. Report set-level MAE, exact count, off-by-one, false positives during rest, missed reps, inter-rep interval error, confidence calibration, and protocol-violation fallback. Emulator transport success is lifecycle evidence only, never sensor accuracy evidence.
