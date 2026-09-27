# Physical-watch install and OTA verification gate

Use this reference when a Wear APK must move from emulator validation to a real Samsung/Wear device and then to self-update.

## Baseline install

```bash
SDK=/path/to/android-sdk
ADB="$SDK/platform-tools/adb"
$ADB devices -l
$ADB mdns services
$ADB -s 'adb-<device>._adb-tls-connect._tcp' install -r wear/build/outputs/apk/debug/wear-debug.apk
$ADB -s 'adb-<device>._adb-tls-connect._tcp' shell dumpsys package dev.kyungmin.kcoach \
  | grep -E 'versionCode=|versionName=|lastUpdateTime'
```

`adb install -r` preserves app data and is appropriate for the baseline test when the existing package uses a compatible signing key. A device can be correctly installed even when `am start` leaves the launcher/SystemUI focused; verify the package and ask the user to launch KCoach from the watch if needed before judging the screen.

## Artifact identity

After the final source edit:

1. Run `:wear:assembleDebug`.
2. Hash `wear/build/outputs/apk/debug/wear-debug.apk`.
3. Pull the installed package path from `adb shell pm path dev.kyungmin.kcoach` and hash the pulled `base.apk`.
4. Only call the installed app the current build when both hashes match.

A matching `versionCode` alone is insufficient because multiple source revisions can share it.

## OTA publication contract

The update client expects a manifest like:

```json
{
  "versionCode": 2,
  "versionName": "0.1.1",
  "apkUrl": "http://<reachable-host>:8765/kcoach-wear-update.apk",
  "sha256": "<lowercase-sha256-of-that-apk>"
}
```

Requirements:

- `versionCode` must be greater than the installed baseline.
- APK `applicationId` and signing key must be compatible with the installed package.
- Manifest URL and APK URL must have the same host and port.
- A physical watch cannot use `10.0.2.2`; use the Mac's LAN IP/hostname.
- The server must listen on a reachable interface, normally `0.0.0.0:8765`.
- Verify both URLs with `curl` and verify the downloaded APK SHA-256 before saying it was uploaded.
- The UI first downloads/checks, then changes to an install action. The first sideload can open Android's unknown-app-source setting; return to KCoach and resume installation.

### Cleartext HTTP bootstrap

For a LAN `http://` update endpoint, target SDK 28+ can reject the request with `CLEARTEXT communication ... not permitted by network security policy` even when the server responds correctly. Verify the Wear manifest's cleartext policy before debugging the server. If the installed baseline lacks that policy, it cannot self-update into a build that adds it: make a same-version, same-signing-key bootstrap APK, install it once with wireless ADB, then build/publish the higher `versionCode` OTA APK. After the bootstrap is installed, turn wireless debugging off and run the check/download/install flow from the watch. Use HTTPS instead of cleartext for a production update service.

If an HTTP request returns timeout/refused/HTTP 000, or if the app reports a cleartext-policy error, the artifact is not currently usable by the watch, regardless of the APK existing locally.
