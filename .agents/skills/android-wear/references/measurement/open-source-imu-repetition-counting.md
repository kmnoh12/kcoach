# Open-source IMU repetition-counting and Wear OS research

Use this reference when researching or designing smartwatch exercise repetition counting, Android/Wear OS capture, filtering, orientation normalization, segmentation, and failure handling. The findings below were verified against public repository files or public developer discussions; they are not accuracy endorsements.

## High-signal repositories

### BangleApps `banglexercise`: causal moving average + slope state machine
- Repo: https://github.com/espruino/BangleApps
- Files:
  - https://github.com/espruino/BangleApps/blob/master/apps/banglexercise/app.js
  - https://github.com/espruino/BangleApps/blob/master/apps/banglexercise/README.md
- `app.js` defines exercise-specific axis selection, thresholds, minimum/maximum inter-rep times, and minimum half-rep duration (`~27-53`). It keeps a six-sample moving average (`~62`, `~109-135`), computes slope (`~138-157`), and confirms a repetition through alternating slope sign crossings with timing gates (`~165-253`). Sampling is set to 12.5 Hz (`~295-296`).
- README explicitly warns that thresholds are experimental and likely do not generalize to everyone.
- Transfer: profile-specific state machines, hysteresis, phase timing, and speed feedback.
- Do not copy blindly: fixed axis/orientation, Bangle-specific scaling/rate, and hardcoded thresholds.

### `werogg/StarJump`: magnitude threshold + relative hysteresis + refractory timer
- Repo: https://github.com/werogg/StarJump
- File: https://github.com/werogg/StarJump/blob/master/app/src/main/java/es/jotero/starjump/events/StarJumpEvent.kt
- Uses `TYPE_LINEAR_ACCELERATION`, vector magnitude, a high threshold (`maxModule > 38`), a relative threshold (`maxModule - 30%`), explicit up/down states, and a 300 ms `CountDownTimer` to prevent duplicate events (`~33-100`).
- Transfer: state ordering, relative hysteresis, and cooldown.
- Do not copy: absolute threshold, phone-in-right-hand assumption, `SENSOR_DELAY_NORMAL`, and Activity-owned lifecycle.

### `gianlucabollo/exercise_tracker`: Butterworth low-pass + local maxima
- Repo: https://github.com/gianlucabollo/exercise_tracker
- Files:
  - https://github.com/gianlucabollo/exercise_tracker/blob/main/count_repetitions.py
  - https://github.com/gianlucabollo/exercise_tracker/blob/main/DataTransformation.py
  - https://github.com/gianlucabollo/exercise_tracker/blob/main/make_dataset.py
- Resamples MetaWear data to about 5 Hz, computes accel/gyro magnitude, applies Butterworth low-pass filtering, then counts `scipy.signal.argrelextrema` maxima. `DataTransformation.py` uses `filtfilt`, which is zero-phase/offline rather than causal. `count_repetitions.py` uses exercise-specific cutoffs and channels.
- The inspected magnitude expressions repeat `acc_y`/`gyr_y` instead of clearly using all three axes; treat as exploratory code, not a production reference.
- Transfer: signal magnitude, exercise-specific channels, phone-side offline reanalysis, temporal/frequency features.
- Do not copy: `filtfilt` into live watch logic, raw local-max counts without distance/prominence/phase gates, or the repository's apparent magnitude bug.

### `nicolabeghin/AccelBench`: timestamped background capture + overlapping feature windows
- Repo: https://github.com/nicolabeghin/AccelBench
- Files:
  - https://github.com/nicolabeghin/AccelBench/blob/master/src/org/unipd/nbeghin/listeners/AccelerometerStoreListener.java
  - https://github.com/nicolabeghin/AccelBench/blob/master/src/org/unipd/nbeghin/services/SamplingStoreService.java
  - https://github.com/nicolabeghin/AccelBench/blob/master/src/org/unipd/nbeghin/models/ClassifierCircularBuffer.java
- Stores XYZ, `event.timestamp`, action, sensor delay, position, and noise threshold. The classifier fills a buffer, computes mean/std/variance, classifies, and retains half the buffer for an overlapping window.
- Transfer: record raw timestamps and capture metadata; measure actual sampling rate; use overlapping windows.
- Do not copy: old service architecture or deadband filtering without measuring information loss.

### `microsoft/Exercise-Recognition-from-Wearable-Sensors`: RecoFit ground-truth structure
- Repo: https://github.com/microsoft/Exercise-Recognition-from-Wearable-Sensors
- Files:
  - https://github.com/microsoft/Exercise-Recognition-from-Wearable-Sensors/blob/main/load_exercise_data.m
  - https://github.com/microsoft/Exercise-Recognition-from-Wearable-Sensors/blob/main/readme.html
- Over 200 participants, 50 Hz accelerometer/gyro. `singleonly` is pre-segmented for recognition/counting; `multionly` includes non-exercise and activity start/end annotations. Annotation columns are exercise, start, end, notes, and repetition count.
- Transfer: keep set segmentation separate from rep counting; preserve non-exercise and start/end labels; evaluate by participant/session.
- License: Community Data License Agreement—Permissive 2.0. Repository is archived.

### `alexhenriquepv/measure-sensor-collector`: raw IMU buffering anti-pattern
- Repo: https://github.com/alexhenriquepv/measure-sensor-collector
- File: https://github.com/alexhenriquepv/measure-sensor-collector/blob/main/app/src/main/java/br/concy/demo/health/MotionSensorsService.kt
- Registers accel/gyro in a foreground service but once per second inserts only `accelBuffer.last()` and `gyroBuffer.last()`, then clears each buffer (`~150-179`). This discards nearly all IMU samples.
- Treat as a warning: foreground service does not imply complete capture. Validate effective sample count, gaps, and timestamps.

### `hissain/sensor-data-collector`: Wear service/Data Layer pattern, HR-only
- Repo: https://github.com/hissain/sensor-data-collector
- Files:
  - https://github.com/hissain/sensor-data-collector/blob/main/wear/src/main/java/com/hissain/android/demo/sensordatacollector/wear/sensor/SensorManager.kt
  - https://github.com/hissain/sensor-data-collector/blob/main/wear/src/main/java/com/hissain/android/demo/sensordatacollector/wear/service/DataCollectionService.kt
  - https://github.com/hissain/sensor-data-collector/blob/main/wear/src/main/java/com/hissain/android/demo/sensordatacollector/wear/service/WearableListenerService.kt
- Uses a foreground service, local CSV storage, command messages, and Data Layer sync. The inspected sensor path is heart-rate-focused, not complete accelerometer/gyro capture; sync sends a whole CSV string via `PutDataMapRequest` without a visible checksum/ACK protocol.
- Transfer: separate command listener and sensor service; persist locally before sync.
- Replace for 워치짐: manifest + Asset/binary capture lane, checksum, sample-count/timestamp validation, ACK, retry, and deduplication.
- License: Apache 2.0.

### Official Android Health Services Exercise Sample
- Repo: https://github.com/android/health-samples
- Files:
  - https://github.com/android/health-samples/blob/main/health-services/ExerciseSampleCompose/app/src/main/java/com/example/exercisesamplecompose/data/ExerciseClientManager.kt
  - https://github.com/android/health-samples/blob/main/health-services/ExerciseSampleCompose/app/src/main/java/com/example/exercisesamplecompose/service/ExerciseService.kt
  - https://github.com/android/health-samples/blob/main/health-services/ExerciseSampleCompose/app/src/main/AndroidManifest.xml
- Demonstrates capability checks, ExerciseClient lifecycle, callbackFlow, prepare/start/pause/resume/end, foreground service ownership, and Android 14+/Wear foreground-service types. README notes synthetic data may be needed to avoid immediate pause when no activity is detected.
- Transfer: platform lifecycle/capability/foreground-service patterns, not custom strength-rep accuracy.
- License: Apache 2.0.

### `giaongo/RepDetect`: camera-only hysteresis analogue
- Repo: https://github.com/giaongo/RepDetect
- File: https://github.com/giaongo/RepDetect/blob/main/app/src/main/java/com/example/poseexercise/posedetector/classification/RepetitionCounter.java
- MediaPipe pose classifier uses separate enter/exit confidence thresholds (`6` and `4`) and increments when confidence exits the pose. This is not an IMU implementation.
- Transfer only the hysteresis pattern. Do not transfer camera landmarks/joint-angle logic to wrist-only IMU.
- License: Apache 2.0.

## Public developer failure reports

Reddit pages and JSON/RSS endpoints were blocked by anti-bot responses during the source pass; do not summarize inaccessible Reddit content as evidence. Accessible discussions:

- Wear OS screen-off pause: https://stackoverflow.com/questions/71659843/sensor-data-pauses-upon-screen-off-even-when-foreground-service-partial-wake-l
  - Reports accelerometer pause after Wear OS 3.2 with foreground service and partial wake lock; answers mention per-app background activity settings and a Google bug.
- Timestamp jumps/device variance: https://stackoverflow.com/questions/51794610/wear-os-android-wear-wrong-timestamps
  - Reports multi-second/minute timestamp jumps on some watches but not another model.
- Unexpected accuracy values: https://stackoverflow.com/questions/55490883/smartwatch-accelerometer-accuracy-issue
  - Reports values such as 79/111 instead of the expected Android accuracy range; unanswered, so treat as a device/data-quality investigation item.
- Sampling rate is a suggestion: https://stackoverflow.com/questions/17663191/android-accelerometer-sampling-rate
  - Answers emphasize that requested delay is not guaranteed and timestamped interpolation may be needed for historical analysis.
- Display timeout throttling: https://stackoverflow.com/questions/35958160/accelerometer-sampling-frequency-changes-during-display-timeout
  - Reports ~50 Hz with display on falling to ~5 Hz after timeout.
- Platform fallback for cadence: https://stackoverflow.com/questions/77673563/cadence-detection-api-for-samsung-galaxy-watch-6-wear-os
  - Developer reports failing to implement cadence from accelerometer; answer recommends Health Services `STEPS_PER_MINUTE` where supported.
- SmartRep review: https://github.com/espruino/BangleApps/pull/3927
  - Maintainer review found packaging/metadata/logic-loading problems despite claimed real-hardware testing; PR was later closed. Deployment verification must prove the algorithm is present and invoked in the artifact.

## 워치짐 transfer rules

1. Watch-side live counting: exercise-specific state machine first; use hysteresis, phase ordering, minimum/maximum period, minimum phase duration, refractory period, and inactivity timeout.
2. Phone-side final analysis: validate monotonic timestamps, effective rate, gaps, sample count, checksum, then re-sample/filter and reanalyze.
3. Keep watch provisional, phone reanalysis, and user-confirmed reps separate; store profile/model version and sensor placement.
4. Use DTW/template matching or HMM-like phase models as later phone-side confirmation layers, not as unvalidated watch-side generic counters. The sampled open-source repos did not provide a complete DTW/HMM smartwatch counter.
5. Test screen-off, ambient mode, battery optimization, service restart, disconnection, wrist orientation, loose strap, left/right wrist, tempo, pauses, partial reps, fatigue, transitions, and non-exercise movements.
6. Do not enable automatic counting for wrist-unobservable exercises merely because a generic classifier produces a number.
7. Treat no-license repositories as research references only until reuse permission is clarified.
