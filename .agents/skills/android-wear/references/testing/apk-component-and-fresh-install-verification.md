# APK component and fresh-install verification

Use this after the final source patch and after any rebuild. It captures a reusable Android/Wear verification pattern from a 워치짐 phone↔Wear emulator run.

## 1. Discover artifact metadata before launching

Do not assume the manifest component is `${applicationId}/.MainActivity`. Android Gradle projects can use an `applicationId` such as `dev.example.app` while Kotlin `namespace` is `dev.example.phone` or `dev.example.wear`.

```bash
AAPT="$ANDROID_SDK_ROOT/build-tools/<version>/aapt"
"$AAPT" dump badging phone-debug.apk | head -2
"$AAPT" dump badging wear-debug.apk | head -2
```

Read the module manifest and namespace, then launch the fully-qualified activity:

```text
applicationId/.<namespace-relative Activity>
```

For example, with application ID `dev.kyungmin.kcoach` and namespaces `dev.kyungmin.kcoach.phone` / `dev.kyungmin.kcoach.wear`, use:

```bash
adb -s <phone> shell am start -n dev.kyungmin.kcoach/dev.kyungmin.kcoach.phone.MainActivity
adb -s <watch> shell am start -n dev.kyungmin.kcoach/dev.kyungmin.kcoach.wear.MainActivity
```

A failed `am start` with `Error type 3` is a component-name lookup failure, not proof that installation or APK contents are broken. Correct the component from manifest/namespace evidence before diagnosing runtime.

## 2. Fresh install and hash identity

For a genuine fresh-install gate, remove the package on each target, install the form-factor-specific APK, then pull the installed base APK and compare hashes:

```bash
adb -s <serial> uninstall <applicationId> || true
adb -s <serial> install -r <assembled.apk>
assembled=$(shasum -a 256 <assembled.apk> | cut -d' ' -f1)
base=$(adb -s <serial> shell pm path <applicationId> | tr -d '\r' | sed -n 's/package://p' | head -1)
adb -s <serial> pull "$base" /tmp/installed-base.apk
installed=$(shasum -a 256 /tmp/installed-base.apk | cut -d' ' -f1)
```

Require `assembled == installed`. Also record `dumpsys package` `versionCode` and `versionName`; do not use a matching versionCode as a substitute for hash identity. Phone and Wear packages may share an application ID while being installed on different targets, so keep their APKs and manifests separate.

## 3. Runtime proof after launch

After starting both Activities:

```bash
adb -s <serial> shell dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity'
adb -s <serial> shell uiautomator dump /sdcard/ui.xml
adb -s <serial> shell cat /sdcard/ui.xml
adb -s <serial> exec-out screencap -p > /tmp/<target>.png
adb -s <serial> logcat -d -v brief
```

Treat these as separate facts:

- `am start` returned success = intent accepted;
- `dumpsys activity` focus = which Activity is actually foreground;
- UI XML/screenshot = what the user can see;
- logcat = crash/service/transport evidence.

If `uiautomator dump` returns an idle-state error or produces an old XML snapshot, recapture after the UI settles and inspect a fresh screenshot. Do not interpret a stale/empty UI dump as an app crash without fresh focus and logcat evidence.

For manual launch-gate products, test the negative path separately: start the workout from the phone while the watch Activity is not open, verify the phone shows a pending/connecting state and no watch Activity was started, then open the watch manually and prove the explicit `WATCH_UI_READY` handshake precedes `WORKING`.

## 4. Evidence boundary

A connected badge, relay socket, service start, or visible workout screen proves only that particular gate. It does not prove capture Asset delivery, checksum validation, ACK-driven cleanup, physical sensors, or Watch accuracy. Preserve the first unverified gate in the ledger and report it explicitly.
