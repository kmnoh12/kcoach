# Android 35 FGS and emulator capture debugging

Session-derived reference for a target-SDK 35 phone↔Wear app with a generic Wear emulator lane.

## Crash reproduction

```bash
ADB=.../platform-tools/adb
$ADB -s emulator-5556 logcat -c
$ADB -s emulator-5556 shell am start -W -n dev.kyungmin.kcoach/.wear.MainActivity
$ADB -s emulator-5556 logcat -d -v threadtime
```

If the app shows a crash dialog, extract the complete `FATAL EXCEPTION` block. A typical Android 14/15 failure for a `health` foreground service identifies the exact missing runtime permission set. Do not diagnose this as a relay or internet failure before reading that block.

## Emulator-only FGS fallback

Keep physical Watch behavior on `health` FGS and request/grant its actual sensor permissions on the target. For a fake generic emulator that intentionally has no body-sensor permission, declare both the physical type and a clearly labeled emulator fallback, for example:

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_HEALTH" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
<service android:foregroundServiceType="health|dataSync" />
```

Select `ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC` only when the runtime is identified as an emulator. This proves emulator lifecycle/control behavior, not physical sensor or Wearable Data Layer behavior.

## Capture send probe

The capture path is commonly called from a service main-looper callback such as `completeSet()`. Never call a socket write synchronously there. On Android, `NetworkOnMainThreadException` may be caught with a null/unhelpful message and look like a generic relay write failure. Run the relay write on a worker thread, then post only state/retry bookkeeping back to the service handler.

Evidence to log per capture:

- capture ID;
- asset byte length;
- relay write result;
- phone receive/parse result;
- verification failure reason, if any;
- ACK send result.

A relay TCP write is only host acceptance. Keep `.kcs`, manifest, and the pending ID until the phone verifies size, SHA-256, KCS version, timestamps, sample count, and manifest bounds, persists the analysis, and sends the matching ACK.

## Heartbeat/termination probe

Test set completion and full session completion separately:

1. `START` → `SET_COMPLETE`: capture and ACK may complete while the service remains alive for REST/next set.
2. `COMPLETE`/`STOP`: clear session ownership, stop sensors/tickers/watchdog, remove the notification, and stop the service.
3. Wait beyond one heartbeat interval and inspect `dumpsys activity services` again.

A listener service that starts the workout owner for every heartbeat can recreate an idle foreground service after `COMPLETE`. Heartbeat handling must immediately stop a newly created runtime service when no session is owned, while preserving it during active work/rest.

## Evidence boundary

Record these independently:

- current source;
- smallest compile/test after the latest patch;
- clean full build/lint;
- assembled and pulled-installed APK hashes;
- emulator control/live;
- emulator-only capture/ACK/cleanup;
- production Wearable Data Layer;
- physical Watch screen-off/recreation/sensor lifecycle.

Never reuse a pre-patch APK hash or label the emulator relay as physical Watch/Data Layer evidence.
