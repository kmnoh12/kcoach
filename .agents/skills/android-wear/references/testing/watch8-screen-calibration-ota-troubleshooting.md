# Watch8 screen, calibration, and OTA troubleshooting

## Symptom-to-cause matrix

| Symptom | First interpretation | Evidence to collect | Typical fix |
|---|---|---|---|
| Workout starts, then wrist rotation/lowering shows a dim blurred screen, small clock, or watch face | Wear OS ambient/screen policy, not automatically a phone↔watch transport failure | `dumpsys window`, focused activity, `dumpsys power`, Wear service state, screen recording/photo during rotation | Set `FLAG_KEEP_SCREEN_ON` while `WORKING`/`RESTING`/`PAUSED`; keep sensors/timers in the foreground service |
| Calibration tab says “watch connected” but tap has no useful effect | Placeholder callback or missing command path | Inspect button callback; trace `connectedNodes`, command path, Wear service receipt, capture persistence | Query nodes, create session ID, send explicit start, collect raw capture, explicit STOP, persist/ACK |
| UI says `1.x 다운로드 중` then APK times out | Manifest succeeded; APK transfer lost network or Wi-Fi power policy turned the radio off | Server log for manifest and APK paths, watch network state, exact timeout/exception, bytes/hash | Bounded Wi-Fi + CPU locks, retries, and a wake-lock duration longer than total retry budget |

## Physical-watch reproduction recipe

1. Record installed package version and APK identity before changing anything:
   - `adb shell dumpsys package dev.kyungmin.kcoach`
   - `adb shell pm path dev.kyungmin.kcoach`
   - pull/hash `base.apk` and compare with the assembled artifact.
2. Start a phone-authoritative workout and capture the focused window before/after rotating the wrist.
3. Capture logcat filtered to the app, `WearWorkoutService`, activity lifecycle, `WindowManager`, and power/ambient messages.
4. Confirm whether the Activity stopped while the foreground service continued. Do not treat the watch face alone as proof that the workout session ended.
5. For calibration, record the exact button label, callback behavior, node count, outgoing command, watch service receipt, capture ID, and phone persistence/ACK.
6. For OTA, clear old assumptions: verify the exact manifest URL, manifest JSON, APK GET log, downloaded byte count, SHA-256, installer launch, and final installed version independently.

## Bootstrap caveat

A client cannot use a Wi-Fi lock/retry implementation that has not been installed yet. If the baseline client itself loses Wi-Fi before downloading its first fixed APK, use a temporary visible bootstrap condition (Wi-Fi set to Always on where the watch exposes it, charger/active screen) or deliver the update through a phone relay. After the fixed client is installed, repeat OTA with debugging disabled and verify that the new client—not only the server—held the network during transfer.

## Calibration honesty gate

A raw calibration capture is not yet a personalized classifier. Store the capture with its session/exercise identity and setup metadata, then separately verify feature extraction, acceptance thresholds, and invalidation conditions. UI copy should say “보정 원본 수집” until personalized thresholds/model parameters have actually been computed and persisted.
