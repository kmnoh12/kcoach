# Service ownership and result gates

## Ownership checklist

1. Locate every actual hardware side effect, not just every class:
   - phone heartbeat scheduler and outbound Wear command send
   - Wear `SensorManager.registerListener` / `unregisterListener`
   - heart-rate callbacks
   - rest/fake-rep/watchdog callbacks
   - raw capture buffering and temporary-file deletion
2. Make the foreground services the only owners. Activities may route commands and render broadcasts, but must not retain a second active listener or timer path.
3. If legacy Activity code cannot be deleted in the same patch, make registration paths inert and confirm no call site can activate them. Treat remaining active legacy paths as an incomplete split.
4. Compile both modules after each ownership patch; then run core tests and assemble both APKs.

## Capture-to-result contract

A phone capture receipt should include:

```text
sessionId
captureId
exerciseId
exerciseName
setIndex
sampleCount
watchReps
phoneReps
confirmedReps
analysisConfidence
```

The receiver should validate and persist the capture/analysis before ACK. The Activity should attach the analysis by `sessionId + exerciseId + setIndex`, not by arrival order. If the result screen has already been created, a late capture receipt must update the same result object and persist it again; it must not create a duplicate set.

## Honest release gates

Report these independently:

- source changed
- core tests passed
- phone/wear compile passed
- phone/wear APK assembled
- APK SHA-256 recorded
- ADB target listed
- APK installed and package version checked
- service/Activity reached the expected phase
- Data Layer Asset and ACK exercised
- physical Watch8 sensors and lifecycle verified

An empty `adb devices -l` and empty `adb mdns services` output is a target-availability blocker. It is not evidence of a code failure, and a successful build is not evidence of installation or end-to-end behavior.

## Common result-UI overclaim

A single persisted `lastWorkoutResult` is useful for resuming the latest result and linking it from the records screen, but it is not a complete workout-history database. Full history requires a durable collection keyed by session ID, with set-level analyses and a read path for older sessions.
