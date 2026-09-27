# Emulator phone↔watch bridge — session reference

## Problem pattern

A generic phone emulator and a Wear OS emulator may both run `dev.kyungmin.kcoach` while Google Play/Wearable Data Layer is unavailable or reports missing Play Store. A green UI badge is not proof of transport. The first implementation also had a broken fallback: the watch tried `10.0.2.2:8766`, while the phone service opened a server socket inside the phone emulator. An `adb forward` on host `127.0.0.1:8766` did not make that in-emulator server a host relay.

## Working emulator lane

- Host Python relay listens on `0.0.0.0:8767`.
- Each emulator connects to `10.0.2.2:8767`.
- Relay broadcasts newline-delimited frames:

```text
/path:::payload\n
```

- Both APKs declare `android.permission.INTERNET`.
- Both apps start a persistent listener service from their launcher activity.
- Outgoing messages use a short-lived TCP connection to the relay; incoming messages use the persistent listener.
- Verify host state with `lsof -nP -iTCP:8767` and expect two established emulator/QEMU connections, not just a listening Python process.

## Protocol shape used in the experiment

Legacy state remains available for compatibility:

```text
1|session|exerciseId|setIndex|reps|heartRate|recording
```

Versioned live state carries the fields required by the UI and state machine:

```text
2|session|exerciseId|exerciseName|setIndex|reps|targetReps|weightKg|heartRate|phase|recording|timestamp
```

Rich commands carry the phone-owned session data:

```text
2|type|session|exerciseId|exerciseName|setIndex|targetReps|weightKg|restSeconds|restEndAtMs
```

Important phases: `WORKING`, `SET_COMPLETE`, `RESTING`, `REST_COMPLETE`.

## Verified path

1. Install phone and watch debug APKs with `adb -s <device> install -r <apk>`.
2. Force-stop both and launch the watch then phone activity.
3. Confirm the watch initially publishes a paired/idle screen and no local exercise-start button.
4. Start a phone routine by direct ADB input.
5. On the emulator, the watch fake driver increments reps and heart rate while working.
6. At target reps, the watch stops the fake driver and emits `SET_COMPLETE`.
7. Phone deduplicates the completion, marks the set, computes one shared `restEndAtMs`, sends `REST`, and enters its rest screen.
8. Watch enters `RESTING`, displays the same-duration countdown, and sends low-rate heart-rate/live state updates.
9. After the deadline, phone advances to the next set/exercise rather than re-sending a capped final set.

Observed evidence from the first working run included:

```text
● 스마트폰과 연결 완료
휴식 중
케이블 익스터널 로테이션
다음 세트 준비 · 45초 휴식
25 / 25회
❤️ 158 bpm
휴식 44초 · 폰과 동기화 중
```

## Debugging checklist

- If watch never leaves idle, check Wear manifest `INTERNET`, relay port, and two established connections before touching UI code.
- If watch changes state but phone label returns to “수신 대기”, an asynchronous generic connection-status callback is overwriting the active live label. While `activeWorkout != null`, do not overwrite the live status surface.
- If the last set repeats after rest, advance `exerciseIndex` after all sets are complete; do not only cap `setIndex` at the last index.
- If only the old state payload appears, the transport may work while the richer UI contract is missing; inspect the version field and live path separately.
- Treat emulator fake reps and heart rate as transport/state-machine evidence only, never as real Watch8 sensor-counting evidence.
