# LAN OTA: `.local` mDNS, IPv6, and emulator fallback

## Symptom

Phone OTA succeeds, but a physical Wear device shows an update failure ending with a connection attempt to `10.0.2.2:<port>`. The watch may be connected to the phone and on the same LAN; `10.0.2.2` is still an emulator-host address and is not a physical-watch route.

## Diagnosis

1. Inspect the live server process and root:

```bash
lsof -nP -iTCP:<port> -sTCP:LISTEN
ps ax -o pid=,ppid=,command= | grep '[u]pdate_server.py'
```

2. Resolve the canonical `.local` hostname with both address families:

```bash
python3 - <<'PY'
import socket
print(socket.getaddrinfo('kcoach-host.local', 8765, socket.AF_UNSPEC, socket.SOCK_STREAM))
PY
```

3. Probe both paths. A common failure signature is IPv4 success plus IPv6 `Connection refused` while the hostname advertises a link-local AAAA record:

```bash
curl -4 -fsS http://kcoach-host.local:8765/update-watch.json
curl -6 -g -fsS http://kcoach-host.local:8765/update-watch.json
```

4. Verify the server is serving the current artifact, not an older auto-started copy. Fetch the manifest and APK, then compare the downloaded SHA-256 to the manifest.

## Fix

Use an IPv6 socket with IPv4-mapped support in the update server, and make the launch/auto-restart script pass `--host ::`. Do not only patch the repository copy: patch or restart the process that actually owns the listening port.

Keep a numeric LAN-IP URL as a later fallback in the Wear updater, but remember that an already-installed old APK cannot use a fallback added only in a newer APK. The first bootstrap still requires the old hostname route to work, a device-side/manual installer path, or a separate companion transfer lane.

## Verification gate

After restart:

```bash
lsof -nP -iTCP:<port> -sTCP:LISTEN
curl -4 -fsS http://kcoach-host.local:<port>/update-watch.json
curl -6 -g -fsS http://kcoach-host.local:<port>/update-watch.json
curl -6 -g -fsS http://kcoach-host.local:<port>/artifacts/wear-debug.apk -o /tmp/wear-served.apk
shasum -a 256 /tmp/wear-served.apk
```

The manifest must expose the bumped Wear `versionCode`/`versionName`, and the downloaded bytes must match its `sha256`. This proves the server and transport gates only; it does not prove installer completion. Mark the Wear install gate green only after the watch visibly completes the Android installer and its installed version is verified.
