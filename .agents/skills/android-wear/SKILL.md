---
name: android-wear
description: 워치짐 phone↔Galaxy Watch work. Use for rep counting or exercise-observability decisions, phone↔Wear session protocol and service ownership, raw-capture transport, emulator or physical-watch testing, LAN OTA delivery, and workout-screen UI or stutter bugs.
---

# 워치짐 phone ↔ Wear

워치짐 is a phone app plus a Wear OS app (`phone/`, `wear/`, shared `core/`) acting as one workout session. The target watch is a Galaxy Watch8.

## Evidence ladder

Every claim sits on one rung. Report the highest rung reached on the **current tree**, and name the first rung not yet reached:

1. source changed
2. tests + compile pass (current tree)
3. fresh APKs assembled after the last patch
4. installed on the target: pulled `base.apk` SHA-256 matches the build
5. runtime: the target walks the intended session states
6. capture integrity: phone verified size, SHA-256, timestamps, sample count
7. ACK/cleanup: phone persisted before ACK; watch deleted temp files after ACK
8. physical Watch8: screen-off, recreation, sensors, timeout, pause/resume, teardown
9. accuracy: labeled real-watch captures vs manual/video counts

Emulator runs reach rung 5–7 as **emulator-only** evidence; they never move rung 8 or 9. An older APK or report is stale once the source changes. After each coherent patch, run the smallest relevant test/compile and fix it before the next patch.

## Measurement honesty

A wrist watch sees wrist acceleration, rotation, orientation, and heart rate. Rep count, wrist-motion metrics, and form are three separate outputs; 워치짐 ships only the first two.

- **Capability key** = exercise variant + protocol + watch side + wear/grip condition + calibration state, never the exercise name alone.
- **Tier**: A wrist-direct auto · B auto-count, limited metrics · C personalized/proxy beta · D manual required. **Validation status** is separate: unvalidated · replay · emulator-only · Watch8 signal · Watch8 accuracy. Tiers move only on real-watch labeled data.
- Lower-body machines (leg press, curl, extension, hip) are D with wrist-only input. D shows `자동 산출 안 함` / `수동 입력 필요`, never a confident zero.
- Honest labels: angular speed = wrist angular speed; relative motion range = normalized wrist-signal amplitude; tempo consistency = repeatability of the wrist signal; signal quality = sensor quality. `formScore`, `formFindings`, `trajectoryConsistency`, `swayScore` stay out of model, wire, UI, and storage (a narrow legacy decoder may read old records).
- Resolve an explicit per-family profile before counting (shared motion families, e.g. one `PRESS_RETURN` detector for flat/incline bench). A scalar acceleration-magnitude threshold is not a rep detector.
- **Start gate**: counting begins after a continuous 2-second stability hold (bounded gyro, bounded gravity drift, no sample gaps); any break resets the hold. Label it a wrist setup gate, not posture recognition.
- Keep watch provisional, phone reanalysis, and user-confirmed reps as separate values, with profile/algorithm version and confidence.
- Confirm the user's exact motion sequence (reference images) before choosing axes or thresholds.

Deeper material: `references/measurement/` (capability matrix, detector pattern, open-source IMU counting, Watch8 audit, cleanup pattern).

## Session architecture

- **Phone is the session authority.** The watch shows an honest idle state (`스마트폰과 연결 완료`, `운동 대기 중!`) and has no competing start button.
- **One owner per concern.** The Wear foreground service owns sensors, heart rate, timers, watchdog, capture buffer, outbox, and cleanup; the phone service owns heartbeat and outbound commands. Activities render snapshots and forward user intent; remove old Activity listeners/timers rather than leaving them inert.
- **Commands**: `START`, `SET`, `REST` (absolute `restEndAtMs`), `PAUSE`, `RESUME`, `STOP`/`END`, `COMPLETE`. Live state carries exercise, set, reps, weight, HR, explicit phase (`WORKING`, `SET_COMPLETE`, `RESTING`, `REST_COMPLETE`), timestamp.
- **Liveness**: phone heartbeat ~3 s; watch watchdog resets to idle after ~12 s of silence. A paired flag is discovery, not liveness.
- **Idempotency keys**: control `messageId`; capture `sessionId+setId+captureId`; completion `sessionId+exerciseSessionId+setIndex`.
- **Cleanup** is idempotent and shared by timeout, STOP, END, COMPLETE, and `onDestroy`: unregister every sensor, stop tickers, drop the notification, stop the service. `SET_COMPLETE` stops capture only. A heartbeat must not revive a service after COMPLETE.
- **Phone durability**: persist a versioned in-progress snapshot at every command boundary and restore it in `onCreate`; clear it only on STOP or durable COMPLETE.
- **Android 14+/targetSdk 35**: background listener starts the workout owner with `ContextCompat.startForegroundService`, which calls `startForeground` at once with `health` type and its permissions. A clearly labeled emulator lane may use `dataSync`.

## Capture transport

Three lanes: reliable control messages, lossy live messages, and a durable manifest + Asset for raw capture (never raw capture in MessageClient).

- **Watch**: persist `.kcs` + manifest before upload, keep it pending and retryable across reconnect/restart, delete only after `CAPTURE_ACK`.
- **Phone**: dedupe → read Asset → verify size/SHA-256 → parse KCS, check increasing timestamps and HR bounds → match sample count/bounds to manifest → persist bytes, manifest, analysis → broadcast → ACK → delete DataItem. Duplicates get ACK only.
- Manifest carries capture/session/set IDs, exercise ID/name, set index, format, sample count, rates, monotonic bounds, byte size, SHA-256, provisional reps.
- Socket or relay I/O stays off the main looper.

References: `references/session/`.

## Testing

- **Fast verification rule (MANDATORY)**: while editing or before a commit, run only a single test tied to the changed class (`--tests '*TargetTest'`) or `:phone:compileDebugKotlin` / `:wear:compileDebugKotlin`.
- **No full-suite / UI / Lint runs unless asked**: until the user explicitly asks, do not run full-module tests (`:phone:testDebugUnitTest`, `:wear:testDebugUnitTest`, `:core:test`), Robolectric screen tests (`:phone:testDebugCompose`), Lint (`:phone:lint`, `:wear:lint`), `-Pkotlin.incremental=false`, or `--max-workers=1`.
- When writing a handoff doc (`docs/HANDOFF_*.md`), do not tell the next agent to rerun the full test/lint gate.

Drive tests through ADB (`uiautomator dump`, `dumpsys window`, `dumpsys activity`, logcat), capturing phase and values after each transition.

- **Emulator lane**: a host TCP relay at `10.0.2.2:<port>` (both APKs need `INTERNET`; not an `adb forward`). Fake reps/HR live behind an emulator check. A generic Wear emulator lacks a real Data Layer, so Asset delivery there uses a labeled emulator capture lane.
- **End-to-end path**: phone start → watch `WORKING` → reps advance → `SET_COMPLETE` → phone and watch `RESTING` with the same deadline → rest ends → next set or completion. REST sync is its own gate.
- **Wear Activity** applies commands in both `onCreate` and `onNewIntent`, with a single visible instance.
- **Physical watch**: find it with `adb devices -l` / `adb mdns services`; compare installed version and hash. During `WORKING`/`RESTING`/`PAUSED` the Activity keeps the screen on while the service keeps ownership. A calibration button proves nothing until the callback runs a real capture-and-persist flow.

References: `references/testing/`.

## LAN OTA delivery

Server uploaded, device downloaded, installer opened, and installed version verified are four separate claims.

- Bump `versionCode`/`versionName` per module for every shipped change; phone and watch use separate manifests (`update.json`, `update-watch.json`) generated from the served APKs with `versionCode`, `versionName`, `apkUrl`, SHA-256.
- Confirm the live server's root (`lsof -nP -iTCP:<port> -sTCP:LISTEN`), fetch both manifests over the LAN host, download both APKs, compare hashes. Manifest and APK share host/port; `apkUrl` follows the request Host.
- A physical watch needs the Mac's current LAN IP on a server bound to `0.0.0.0`. HTTP on LAN needs cleartext allowed in the app; an installed baseline missing a fix (cleartext, new IP fallback) needs a one-time bootstrap install.
- Direct watch downloads hold a bounded `WifiLock` + wake lock with retries.
- Admin changes (IP aliases) and passwords go to Kyungmin.

References: `references/delivery/`.

## UI

- Preserve approved layouts. The phone rest area keeps its existing `휴식 시간` text, `−/+`, pause, and stop controls; update seconds in place with at most one thin yellow progress bar. Changing rest during a countdown updates config, the live `restEndAtMs`, and the watch deadline together.
- Stable connection status and live telemetry use separate views.
- Watch rest ring: square viewport center, start at 12 o'clock, clockwise; heart rate sits below primary content in red.
- **Stutter**: reproduce add/delete/complete/uncomplete set separately; look for full-screen rebuilds, GIF/bitmap decoding, sync I/O, or timer/sensor restarts in click paths; measure with `dumpsys gfxinfo` or Perfetto; fix by updating only the changed row and caching drawables; verify on the installed new APK.
- For screenshot bugs, state what is visible first (a tiny asset is tiny, not missing).

References: `references/ui/`.

## Scope

Follow the device and delivery scope Kyungmin names: watch-only means the phone artifact stays untouched; server-only OTA means publish and verify without an ADB install. Install on a device only when he asked for it.
