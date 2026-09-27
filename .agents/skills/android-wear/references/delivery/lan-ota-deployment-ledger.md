# LAN OTA deployment ledger

Use this ledger whenever a phone + Wear pair must receive a Wi-Fi OTA update.

## 1. Version gate

Read both modules before changing anything:

```bash
rg 'versionCode|versionName' phone/build.gradle.kts wear/build.gradle.kts
```

Bump both shipped modules when behavior changes. Keep phone and Wear version codes independent, but never publish changed bytes with an equal/older code because the in-app updater rejects them.

## 2. Fresh artifact gate

Run the current tree, not an earlier build:

```bash
gradle test :phone:assembleDebug :wear:assembleDebug \\
  :phone:lintDebug :wear:lintDebug --no-daemon
```

Record each artifact's size and SHA-256:

```bash
shasum -a 256 phone/build/outputs/apk/debug/phone-debug.apk \
  wear/build/outputs/apk/debug/wear-debug.apk
```

## 3. Discover the live server root

Do not assume the canonical repository build directory is what port 8765 serves:

```bash
lsof -nP -iTCP:8765 -sTCP:LISTEN
ps ax -o pid=,command= | grep -E '[u]pdate_server.py|[K]CoachUpdateServer'
```

Inspect the process command and its `KCOACH_UPDATE_ROOT`/script path. If it serves a separate root, either copy both fresh APKs and matching version metadata into that root or stop/restart it with the canonical root. A dynamic server that reads its own Gradle files will otherwise report stale version metadata even after the APK copy.

## 4. Publish and verify

The KCoach update server exposes:

```text
/update.json
/update-watch.json
/artifacts/phone-debug.apk
/artifacts/wear-debug.apk
```

Verify with the LAN host, not only loopback:

```bash
curl -fsS -H 'Host: <canonical-host>:8765' \
  http://<lan-ip>:8765/update.json
curl -fsS -H 'Host: <canonical-host>:8765' \
  http://<lan-ip>:8765/update-watch.json
curl -fsS -H 'Host: <canonical-host>:8765' \
  http://<lan-ip>:8765/artifacts/phone-debug.apk -o /tmp/phone-served.apk
curl -fsS -H 'Host: <canonical-host>:8765' \
  http://<lan-ip>:8765/artifacts/wear-debug.apk -o /tmp/wear-served.apk
shasum -a 256 /tmp/phone-served.apk /tmp/wear-served.apk
```

The manifest's `versionCode`, `versionName`, `apkUrl`, and SHA-256 must match the downloaded served bytes. A `200` response alone is not an upload/ready proof.

## 5. Keep installation claims separate

Record four independent gates:

```text
server uploaded: manifest and served APK hashes match
on-device download: updater downloaded and verified the APK
installer opened: Android installer screen appeared
installed: target package reports the new versionCode/versionName
```

`adb devices` being empty does not invalidate the LAN server gate, but it prevents agent-verified installation. In that case the user must trigger the in-app updater:

- Phone: KCoach → Menu → Update check → Download complete · Install
- Wear: KCoach → Update check → Update install

The first installation may require allowing KCoach as an unknown app source. Do not say "installed" from build, server, manifest, or download evidence alone.
