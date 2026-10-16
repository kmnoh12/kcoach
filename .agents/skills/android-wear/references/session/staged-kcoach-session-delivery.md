# Staged 워치짐 Session Delivery Reference

## Phase ledger template

Use one row per phase and update it after the immediate verification command:

| Phase | Scope | Last verified gate | Not yet verified |
|---|---|---|---|
| Shared contract | wire envelope, IDs, payload parse | core tests + phone/wear compile | real transport |
| Capture/replay | KCS format, checksum, replay | core round-trip/truncation tests | Watch file creation |
| Service migration | single owner for state/sensors/timers | module compile + lifecycle test | physical screen-off |
| Capture transport | manifest, Asset, checksum, ACK | phone/wear compile + integration fixture | physical Data Layer |
| Analysis | deterministic phone detector | replay fixtures | real labeled Watch8 data |
| Result UI | final set/session presentation | UI/state tests | dogfood on device |
| Device verification | emulator then Watch8 | ADB/runtime evidence | release OTA gate |

## Ownership invariant

Before declaring Activity → Service complete, search for every registration and mutation site:

```text
sensorManager.registerListener
sensorManager.unregisterListener
Handler.post
Handler.postDelayed
Runnable
activeSession
sessionId
restEndAtMs
onSensorChanged
sendLiveState
cleanup
```

There must be one active runtime owner for each concern. It is acceptable for the Activity to retain a read-only view model or compatibility adapter; it is not acceptable for it to continue registering sensors or running a second timer.

## Capture manifest minimum

```text
protocolVersion
messageId
sessionId
exerciseSessionId
setId
captureId
formatVersion
compression
sampleCount
sampleRateRequestedHz
sampleRateObservedHz
startMonotonicNs
endMonotonicNs
sha256
assetSizeBytes
```

Phone acceptance sequence:

```text
receive manifest
→ validate protocol/session/set/capture identity
→ receive Asset
→ read exact bytes
→ compare byte size
→ SHA-256 compare
→ decode KCS
→ compare sample count and timestamp bounds
→ persist capture metadata/file
→ send ACK(messageId,captureId)
```

Watch deletion is allowed only after a matching ACK. If the phone rejects the capture, retain the file as `PENDING_UPLOAD` or `FAILED`; never silently discard it.

## Tool and build discipline

- Read the actual source and manifest before patching.
- After a core change, run core tests.
- After a shared-model change, compile both phone and wear.
- After a manifest/service change, compile the affected module and inspect merged manifest if needed.
- After an Asset API change, compile the actual dependency version before writing the next layer.
- If the tool budget or session ends, report the most recent command *after* the last patch, not the last successful command from earlier work.

## Runtime evidence levels

1. **Compile evidence** — Kotlin/Gradle accepts the source.
2. **Unit/replay evidence** — deterministic core behavior passes.
3. **Emulator transport evidence** — command/live/capture path works through the chosen relay/Data Layer.
4. **Installed artifact evidence** — target package contains the current APK/version/hash.
5. **Physical sensor evidence** — Watch8 produces labeled data and lifecycle behavior is observed.

Never summarize level 1 as level 5.
