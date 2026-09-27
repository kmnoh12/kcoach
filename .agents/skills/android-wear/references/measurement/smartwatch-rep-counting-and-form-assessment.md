# Source-grounded notes: smartwatch rep counting and form assessment

## Bottom line

Commercial smartwatch rep counters generally detect a repeated wrist/forearm sensor pattern, not a full-body canonical posture. Keep `rep event`, `range/tempo consistency`, and `technique/form quality` as separate claims.

## Commercial disclosures

- **Motra** App Store description: says it monitors Apple Watch motion, tracks reps for 470+ exercise types, and uses proprietary/patented “Neural Kinetic Profiling.” It does not disclose a full-body pose or joint-angle verifier.
  - https://apps.apple.com/us/app/motra-ai-workout-fitness-coach/id1548577496
- **Gymatic** App Store description: says it automatically identifies exercises, counts reps, logs workouts, exposes tempo/rep speed and “form consistency”/“range consistency,” can train a new exercise in about three seconds, and advises moving the sensor to the leg for leg press. This supports a learned sensor-signature interpretation, not a universal canonical-form conclusion.
  - https://apps.apple.com/us/app/gymatic-workout-tracker/id1036069872
- **Fitnexx** App Store description: combines watch-based tracking with camera detection for push-up distance, illustrating that some exercises need a different sensing modality.
  - https://apps.apple.com/us/app/fitnexx-workout-reps-counter/id1457980771

Marketing descriptions are not independent validation. Preserve vendor claims separately from measured performance.

## Wearable research

### Deep-learning smartwatch pipeline

“Recognition and Repetition Counting for Complex Physical Exercises with Deep Learning” used accelerometer, gyroscope, and orientation streams from smartwatches. The pipeline:

1. rolling sensor windows → exercise classifier;
2. exercise-specific repetition classifier;
3. binary repetition-start output;
4. smoothing and counting valid 1-sequences.

It reported ±1 repetition error for 91% of sets in its constrained study conditions. It also reported weaker transfer when repetitions became fast and transitions became smooth. The model detects learned signal events; the paper does not establish full-body form correctness.

- PMC: https://pmc.ncbi.nlm.nih.gov/articles/PMC6387025/

### Template/DTW and sensor placement

“ExerSense” segmented synthetic acceleration using peaks, compared motion segments with exercise templates using Dynamic Time Warping, classified the best match, and incremented the counter. In its comparison, chest placement performed best and wrist placement was weaker. Push-ups were particularly difficult at the wrist because the wrist moved little.

- PMC: https://pmc.ncbi.nlm.nih.gov/articles/PMC7795271/

### Apple Watch strength-training validation

A StrengthControl validation using a first-generation Apple Watch recognized the correct strength exercise in 88.4% of performed sets. Squat/deadlift count agreement was acceptable in that setting, while bench-press repetition counting was poor. The study attributed errors to differences in execution, range/body dimensions, sensor limitations, and data-transfer problems.

- PMC: https://pmc.ncbi.nlm.nih.gov/articles/PMC8471343/

## Platform evidence

- Apple Core Motion exposes raw three-axis accelerometer data and device-motion interfaces. A watch app must implement its own exercise-specific recognition/counting logic.
  - https://developer.apple.com/documentation/coremotion/getting-raw-accelerometer-events
- Wear OS Health Services brokers sensors and derives common fitness metrics for apps. It does not imply that every app receives a universal strength-training rep/form classifier.
  - https://developer.android.com/health-and-fitness/health-services

## Camera contrast

A camera-based research system (“Pūioio”) used pose estimation, thresholding, optical flow, a state machine, and a counter, reporting high accuracy in its own smartphone-camera evaluation. This approach can observe more body geometry than a wrist IMU, but requires usable camera framing, visibility, and calibration.

- https://arxiv.org/abs/2308.02420

## KCoach implications

For Watch8-only delivery, gate automatic counting by exercise observability. Use `watchRepProvisional`, `watchRepConfirmed`, confidence, exercise ID, set/session ID, sensor placement, and model version. Keep form/ROM assessment source explicit (`watch`, `camera`, `multi_sensor`, `manual`, `none`). Emulator/fake telemetry can verify transport and state-machine transitions but cannot validate physical observability or form quality.
