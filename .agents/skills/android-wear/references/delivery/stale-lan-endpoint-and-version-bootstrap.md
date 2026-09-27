# Stale LAN Endpoint and Version Bootstrap

Use this reference when an Android phone or Wear device reports an update failure against an old Mac IP, or when the UI still shows an old version after source changes.

## Evidence-first probe

Run on the Mac before changing the app:

```bash
ifconfig en0 | grep -E 'inet |status:'
lsof -nP -iTCP:8765 -sTCP:LISTEN
curl --max-time 5 -fsS http://<current-mac-ip>:8765/update.json
curl --max-time 5 -fsS http://<old-configured-ip>:8765/update.json
```

Interpret the results separately:

| Observation | Meaning | Next move |
|---|---|---|
| Current IP responds; old IP times out | DHCP/address drift; old baseline cannot reach current server | Bootstrap/manual install, restore old address, or explicitly add a temporary alias after conflict check |
| Both IPs fail | Server is absent, wrong port, firewall, or wrong interface | Start/bind server, verify listener, then retest |
| Manifest responds but APK hash differs | Stale or mismatched artifact | Re-copy APK and regenerate manifest hash; fetch both again |
| `CLEARTEXT ... not permitted` | App policy blocks HTTP before network access | Build same-version cleartext bootstrap; install it once via ADB |
| `10.0.2.2` on physical device | Emulator fallback is being tried/reported; it is not a physical-device route | Use a reachable LAN host; do not treat the fallback error as proof that the LAN URL was reachable |

## Version proof

Do not infer a version from a screen or source diff alone. Record:

```bash
grep -E 'versionCode|versionName|UPDATE_MANIFEST_URL' phone/build.gradle.kts
python3 -c 'import json; print(json.load(open("phone/build/outputs/apk/debug/output-metadata.json")))'
adb -s <target> shell dumpsys package dev.kyungmin.kcoach | grep -E 'versionCode=|versionName='
shasum -a 256 phone/build/outputs/apk/debug/phone-debug.apk
```

The code may be newer while `versionName` remains unchanged. A normal release gate increments `versionCode`, updates `versionName`, assembles the APK, installs or serves that exact APK, and checks the manifest's version and SHA-256 against the downloaded bytes.

## Bootstrap sequence

1. Keep the installed baseline's `versionCode`/`versionName` while adding the required network policy or corrected endpoint.
2. Build the bootstrap APK with the same application ID and signing key.
3. Install it once through ADB or another explicit sideload path.
4. Restore the next release's higher `versionCode`/`versionName` and endpoint.
5. Build the real update APK and publish a device-specific manifest (`update.json` for phone, `update-watch.json` for Wear).
6. Verify manifest and APK from the Mac, then from the target network where possible.
7. Test the in-app flow: check → download → hash verification → install permission → Android installer.

## Temporary IP alias

Only use an alias when the old literal IP is known to be unused and the user explicitly chooses this workaround. Check for an existing responder first; no ping response is useful evidence but not an absolute conflict guarantee. The command requires the user to authorize locally; the agent must never handle the password:

```bash
sudo ifconfig en0 alias <old-ip> netmask 255.255.255.255
# verify
route -n get <old-ip>
curl --max-time 5 -fsS http://<old-ip>:8765/update.json
# remove after the test
sudo ifconfig en0 -alias <old-ip>
```

On macOS, use a `/32` host mask for an alias on the same subnet. Adding the alias with the full LAN `/24` mask can create a `REJECT` route and produce `Host is down` even though `ifconfig` lists the address. If that happens, remove the `/24` alias first, re-add it with `255.255.255.255`, and verify that `route -n get <old-ip>` no longer shows `REJECT`. A server already bound to `0.0.0.0:8765` should accept the aliased address. Verify the old URL and manifest before asking the user to press Update. Remove the alias after the test to avoid future IP conflicts.
