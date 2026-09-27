# Phone ↔ Watch lifecycle safety reference

Use this reference for safety-critical workout sessions where a Wear OS app must stop when the phone app is no longer alive.

## Failure pattern

A one-time `HELLO`/node connection is not proof of liveness. A watch-side `paired=true` flag can remain stuck forever after the phone process or activity disappears, leaving sensors, fake-rep tickers, rest timers, and rings running.

## Recommended protocol

- Phone sends a dedicated heartbeat every ~3 seconds while the phone workout/session owner is alive.
- Watch records `lastPhoneHeartbeatAtMs` and runs a watchdog every few seconds.
- If no heartbeat arrives for a bounded timeout (12 seconds in the KCoach implementation), reset the watch to disconnected/idle.
- Heartbeats must not launch or foreground the watch activity on every tick. Route them through an internal broadcast or an existing service/activity state owner.
- Use explicit lifecycle commands: `START`, `SET`, `REST`, `PAUSE`, `RESUME`, `STOP`/`END`, and `COMPLETE`.

## Required reset behavior

On heartbeat timeout, `STOP`/`END`, or `COMPLETE`:

1. Stop fake-rep and rest ticker callbacks.
2. Unregister accelerometer, gyroscope, and heart-rate listeners; do not unregister only motion sensors.
3. Stop recording and clear the rest deadline/ring.
4. For timeout, clear connection/session state and show phone-connection waiting; do not auto-resume the old session on reconnection.
5. For completion, send the final live state and a completion summary before clearing the in-memory session; include a session ID, phase, final reps/heart rate, and sample count or equivalent acknowledgement data.

## Pause semantics

Pause must be a cross-device state transition, not a phone-only boolean. Freeze any active rest deadline as remaining milliseconds, stop watch capture/tickers, and restore the remaining deadline on resume. Avoid re-sending `SET` while paused or while a rest countdown is active because that can reset the watch set and bypass synchronized rest.

## Verification recipe

- Build both artifacts after shared protocol changes.
- Start a real or emulator session and prove `WORKING → PAUSED → WORKING`, `WORKING/RESTING → STOPPED`, and `WORKING/RESTING → COMPLETE`.
- Force-stop or otherwise terminate the phone owner, wait longer than the watchdog timeout, and verify the watch changes from connected/active to disconnected/idle and stops its timers/sensors.
- Verify final completion data is received by the phone before judging the watch reset successful.
- Keep emulator fake repetitions and real-watch sensor/form accuracy as separate claims; transport/lifecycle passing does not validate exercise classification or FormAnalyzer/FM integration.
