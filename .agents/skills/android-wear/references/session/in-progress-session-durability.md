# In-progress session durability and set-completion triage

A phone↔Wear workout can appear to work until the first automatic set completion because that boundary combines several asynchronous operations: the watch emits `SET_COMPLETE`, the phone mutates its planned-set model, the phone starts synchronized REST, the Wear side flushes/publishes capture data, and the phone rebuilds or refreshes the active UI.

## Evidence split

A relaunch showing the home screen is not proof of a crash. Separate the cases:

- **Actual crash:** `AndroidRuntime` / crash buffer contains `FATAL EXCEPTION`, with a stack trace near completion or capture handling.
- **Activity recreation:** Activity destroy/create or task recreation appears without a fatal exception; the Activity kept the session only in memory.
- **Process death:** the process is killed/restarted without an app exception, often while a service/outbox may remain alive.
- **Intentional completion/stop:** an explicit `COMPLETE` or `STOP` path cleared the active session.

Capture Android runtime/crash logs and Activity/process lifecycle logs around the same monotonic time. Add structured markers before and after each boundary: completion receipt, idempotency-key check, session mutation, durable snapshot write, capture save/analysis, REST dispatch, and UI rebuild.

## Durable active-session contract

Completed-result persistence is not active-session persistence. Store a versioned active snapshot independently of historical results. At minimum it should contain:

```text
schemaVersion
sessionId
routineId
exerciseIndex
setIndex
planned-set completion flags and editable values
performed reps by exercise
phase: WORKING | SET_COMPLETE | RESTING | PAUSED
restEndAtMs
startedAtMs and pause accounting
last accepted completion key
```

Write the snapshot atomically before/around every lifecycle boundary (`START`, `SET`, `SET_COMPLETE`, `REST`, `PAUSE`, `RESUME`, `STOP`, `COMPLETE`). On phone startup, restore it and reconcile against the session service's state and the latest accepted wire event. Do not delete it merely because the Activity was stopped or recreated. Clear it only after an explicit stop or after the completed-session summary and capture references are durable.

## Idempotent set-completion sequence

For `sessionId + exerciseId + setIndex`:

1. Reject a duplicate completion key without re-starting rest.
2. Mutate the planned set and performed-rep counters.
3. Persist the updated active snapshot.
4. Compute and persist the absolute rest deadline.
5. Dispatch `REST` with the same deadline to the watch.
6. Render REST from the persisted deadline; UI rebuilding must not be the owner of the timer.
7. Let capture/analysis receipts update the session/result idempotently.

A heartbeat service that only sends liveness messages or outbound commands does not recover a phone UI session unless it also owns or can restore this durable snapshot.
