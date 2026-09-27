# Evidence ledger and emulator Asset lane

Use this as a phase ledger for a phone↔Wear workout delivery run. Replace placeholders with the repository's current paths and serials; do not reuse stale output.

## Phase ledger

```text
phase: profile policy
source: current tree
proof: profile resolver + replay tests

phase: current build
command: gradle test :phone:assembleDebug :wear:assembleDebug :phone:lintDebug :wear:lintDebug
proof: task-level BUILD SUCCESSFUL after the last source change

phase: install identity
commands:
  adb -s <phone> install -r <phone-apk>
  adb -s <wear> install -r <wear-apk>
  adb -s <target> shell pm path <application-id>
  adb -s <target> pull <base.apk> <temp>
proof: assembled and pulled base.apk SHA-256 equal, package/version match

phase: control/live
proof: phone START → watch WORKING → SET_COMPLETE → RESTING → next set/complete
not proof: paired badge, relay LISTEN, or successful am start alone

phase: emulator capture
proof: phone receives manifest+bytes, verifies KCS/hash/count/timestamps, persists analysis, sends ACK, Wear removes pending files
label: emulator transport/lifecycle only

phase: physical Watch
proof: real Watch screen-off/recreation/sensors/heartbeat/timeout/pause/resume/cleanup
```

## Fresh-target triage

Run these before diagnosing an app or network issue:

```bash
adb devices -l
adb -s <serial> shell getprop sys.boot_completed
adb -s <serial> shell dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'
adb -s <serial> logcat -c
adb -s <serial> shell am start -n <package>/<activity>
adb -s <serial> logcat -d -v brief | grep -E -A35 -B8 'FATAL EXCEPTION|AndroidRuntime|Process:'
```

If `adb` reports `waiting for device`, first recover the device/emulator and rerun the logcat command. Do not infer an Internet cause from an ADB disappearance. If the app shows `keeps stopping`, capture the fresh crash stack before changing code.

## Emulator capture envelope

The emulator-only envelope is intentionally separate from Google Play/Wear Data Layer:

```text
<asset-path>:::<captureId>|base64(wireManifest)|base64(kcsBytes)
```

Rules:

- Base64 payload must not contain the relay delimiter (`|`).
- The phone decodes the manifest and bytes and reuses the production verification function.
- The phone checks that the envelope capture ID equals the manifest capture ID.
- The phone persists capture/manifest/analysis before setting its ACK-dedup flag.
- The ACK uses the normal wire protocol and returns through the relay.
- The Wear side deletes `.kcs` and `.manifest` only after matching capture ACK.
- Duplicate delivery must be ACK-only after the first persisted result.

Inspect both sides after a run:

```bash
adb -s <phone> shell run-as <package> ls -la files/captures
adb -s <wear> shell run-as <package> ls -la files
adb -s <phone> shell dumpsys activity services <package>
adb -s <wear> shell dumpsys activity services <package>
```

Expected final state for a completed session: phone capture and analysis persisted; Wear pending capture files absent; no motion sensors registered; no workout foreground service/notification remaining. If the service is intentionally restarted to process a late ACK, verify it stops again after cleanup.

## Acceptance matrix

| Claim | Required evidence | What it does not prove |
|---|---|---|
| profile selected | exercise-specific resolver + tests | physical accuracy |
| build green | fresh Gradle output | install/runtime |
| installed artifact | pulled APK hash equals assembled hash | behavior |
| control/live | phase/state observations | raw Asset delivery |
| emulator capture | phone hash verification + ACK + file cleanup | real Data Layer or Watch sensors |
| physical capture | Watch/phone Asset logs + files + ACK | classifier accuracy unless labeled |
| form feedback | result card carries source/profile/metrics/corrections | whole-body posture |

## Exercise claim boundary

For pull-up, report a wrist-IMU beta/caution profile until labeled Watch8 captures show hang/ascent/top/descent, ROM, pause, and kipping behavior. For machine leg press and similar lower-body machines, keep wrist-only automatic counting disabled. A wrist-derived angular speed, relative ROM, or sway score must be labeled as a wrist metric, not joint speed, joint ROM, torso sway, or camera posture.
