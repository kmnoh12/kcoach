# Interrupted 워치짐 handoff and Android 14 verification gates

Use this reference when a desktop/cron Hermes session reset before the phone↔Wear task was declared complete.

## Handoff procedure

1. Re-discover the canonical repo and inspect current source, not `build/` alone.
2. Maintain a gate ledger with separate rows for source, fresh tests, APK assembly, install/version/hash, runtime state, capture Asset integrity, ACK/cleanup, and physical Watch behavior.
3. State the prior agent's last claimed gate separately from gates newly verified in the current session.
4. Never treat a previous APK, a connected badge, or an emulator fake-rep run as physical Watch8 evidence.

## Android 14+/targetSdk 35 service gate

A command arriving through a background `WearableListenerService` cannot reliably launch the workout owner with plain `startService(...)`. Dispatch with `ContextCompat.startForegroundService(...)`; call `startForeground(...)` immediately in the workout service.

The manifest must match the service type. For a health sensor owner, declare `android:foregroundServiceType="health"`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_HEALTH`, and the sensor/recognition permissions required by the target. On a generic emulator, explicitly grant the dangerous test permissions before starting the service if the app intentionally skips emulator permission UI. Keep this emulator setup separate from real-device acceptance, where the user must approve the permissions normally.

Typical failure evidence:

```text
Background start not allowed ... WearWorkoutService
SecurityException: Starting FGS with type health ... requires BODY_SENSORS/ACTIVITY_RECOGNITION/...
```

These are implementation/target-gate findings: fix the dispatch path and permission gate, then repeat the runtime test. Do not stop at a successful compile.

## Emulator lane

Use a host relay only for control/live state, for example a relay listening on `0.0.0.0:8767` with newline-delimited `/path:::payload` frames. Verify the listener is not merely bound: inspect host sockets and require established connections from both emulators. Drive the phone UI (routine → start → confirm) and capture both phone/watch UI state and persisted phone state after `WORKING`, target completion, `RESTING`, and the next-set transition.

Fake reps and fake heart rate prove only the lifecycle/transport lane. A generic Wear image without a usable Wearable Data Layer/Play Store cannot prove Asset delivery. Keep `capture manifest → Asset read → SHA-256/sample/timestamp validation → persisted analysis → ACK → watch temp-file deletion` as an explicitly separate gate until a real Data Layer target or a purpose-built, clearly labeled emulator Asset lane exercises it.

## Capture checks that must remain explicit

- Manifest carries capture ID, session/set identity, exercise ID/name, set index, KCS format/compression, sample count, observed rate, monotonic start/end, byte size, and SHA-256.
- Watch buffering rejects or skips non-increasing sensor timestamps before persistence.
- Watch keeps a retryable KCS + manifest outbox across reconnect/process restart.
- Phone validates the manifest and bytes before persisting analysis.
- Phone sends an idempotent ACK only after persistence; watch deletes temporary files only after ACK success.
- Duplicate delivery is ACK-only and must not produce a second analysis.
