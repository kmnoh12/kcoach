# Rotation detector and set-end diagnostics

Use this reference when a Wear app falsely counts shaking, misses a slow forearm rotation, or shows a Samsung/SystemUI prompt immediately after a set.

## Recognition gate

1. Search the current source for scalar acceleration thresholds used as universal rep triggers. Remove or quarantine them; acceleration magnitude is an impact/shaking detector, not an exercise classifier.
2. Resolve a versioned exercise profile from both ID and localized display name. Include Korean transliterations such as `익스터널 로테이션`, not only English or a formal Korean translation.
3. For cable external rotation, model one rep as a controlled out-and-back cycle: lock a dominant gyro axis, integrate signed angular-velocity lobes, require minimum angular displacement and duration, reject high dynamic acceleration/axis instability, and flush a completed final lobe at STOP/set end.
4. Use replay tests for slow/normal/fast cycles, partial ROM, pauses, axis changes, acceleration-heavy shaking, and missing timestamps. Keep the profile beta until Watch8 samples are compared with video/manual counts.
5. For every other unvalidated exercise, return `MANUAL_REQUIRED` and show `자동 산출 안 함` / `수동 입력 필요`; do not let a generic fallback end the set automatically.

## Set-end/system-dialog gate

1. Search for app-side cache deletion APIs (`clearCache`, recursive cache deletion, or equivalent). If absent, do not claim the app requested a cache clear.
2. Instrument `completeSet`, `finishSession`, capture serialization, sensor unregister, `stopForeground`, and `onDestroy` with session/set/capture IDs, sample count, and exception stack traces. Do not swallow `runCatching` failures without logging.
3. Reproduce on the physical Watch8 while collecting filtered `logcat` (`AndroidRuntime`, `FATAL EXCEPTION`, `ANR`, app package, SystemUI) and a screen recording. Classify the prompt as app crash, ANR, package/update UI, or SystemUI only after correlating timestamp and focused window.
4. Keep capture persistence retryable until phone validation and ACK. Do not clear the only capture copy at `SET_COMPLETE`; keep the service alive for REST and the next set.

## OTA artifact gate

When the server serves a staging/runtime copy, verify that the runtime's version source and APK are both refreshed. A new APK copied over an old runtime `build.gradle.kts` can produce a stale manifest version even though the bytes are new. Read the served manifest, download the exact `apkUrl`, compute SHA-256, and compare APK badging with manifest `versionCode`/`versionName` before calling OTA ready.
