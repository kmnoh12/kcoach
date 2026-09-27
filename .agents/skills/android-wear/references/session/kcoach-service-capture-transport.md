# Service-owned session and KCS Asset transport recipe

This reference records the reusable implementation pattern from a phone↔Wear workout session with raw sensor capture.

## Ownership boundary

```text
Phone Activity      UI, user intent, service-state rendering
PhoneWorkoutService foreground lifetime, heartbeat, outbound Wear commands
PhoneWearListener   MessageClient/DataClient receive, validation, persistence, ACK
Wear Activity       UI only; forwards commands and renders service broadcasts
WearWorkoutService  session state, sensors, HR, timers, provisional reps, capture
```

Do not call the Wear bridge directly from the phone Activity once the service exists. Queue a path/payload intent to the phone service. On Wear, do not leave a second Activity-owned `SensorEventListener`, watchdog, or rest timer active; dead duplicate code can later become a second owner after a lifecycle change.

## Capture flow

1. At set end, write the watch capture to an app-private `.kcs` file and keep it until ACK.
2. Build a versioned capture manifest containing session/set/capture/message IDs, sample count, monotonic start/end, asset byte size, and SHA-256.
3. Put a Wear `DataItem` with the encoded manifest and `Asset.createFromBytes(...)` (or an asset-backed file) on the capture path.
4. On phone `onDataChanged`, accept only `TYPE_CHANGED` and the expected capture path. Decode the manifest and identify the source node from the DataItem URI.
5. Read the Asset through `DataClient.getFdForAsset(asset)`. Release the response after the stream is consumed.
6. Validate, in order: byte size, SHA-256, KCS decode/timestamps/heart-rate ranges, and manifest sample count. Reject before persistence or analysis.
7. Persist the original bytes and analysis result. Keep watch provisional reps separate from phone reanalysis and confirmed reps.
8. Send a MessageClient ACK carrying the original message ID and capture ID. Mark the capture ACKed and delete the phone DataItem only in the ACK success callback.
9. On Wear receipt of `CAPTURE_ACK`, delete the matching temporary `.kcs` file. If ACK or validation fails, retain the file for retry.
10. If the same capture is delivered again after successful ACK, send ACK-only and do not re-run analysis.

## Verification matrix

| Gate | Evidence | Does not prove |
|---|---|---|
| Core test | deterministic KCS/analyzer tests pass | Data Layer works |
| Kotlin compile | phone/Wear compile succeeds | APK is installed |
| APK assemble | both APK files exist and hash is recorded | target runs current source |
| ADB install | `pm path`/version and pulled hash match | sensor callback correctness |
| Data Layer test | Asset received, validated, ACKed, temp file removed | full real exercise validity |
| Watch8 run | lifecycle/sensor/timeout behavior observed on target | model/general exercise accuracy |

If `adb devices -l` is empty, report device verification as blocked; do not convert a successful build into a physical-device claim.

## Common failure modes

- **Manifest arrives but bytes are absent:** keep the manifest as pending; do not fabricate a successful capture. Retry DataClient Asset retrieval.
- **Hash or sample mismatch:** emit a capture-error event, retain the watch file, and do not ACK.
- **ACK sent before persistence:** a retry can delete the only source before analysis is durable. Persist and analyze first.
- **Phone DataItem deleted before ACK success:** a transient link failure loses the retry source. Delete only from the success callback.
- **Activity and service both register sensors:** counts/timestamps and cleanup become nondeterministic. Remove the duplicate listener before device testing.
- **UI says connected after heartbeat loss:** use recent heartbeat plus actual transport state, and reset the watch session on bounded silence.
