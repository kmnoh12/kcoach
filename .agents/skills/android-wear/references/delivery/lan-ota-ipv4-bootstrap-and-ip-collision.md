# LAN OTA IPv4 bootstrap and IP-collision checklist

Use this when a physical Wear updater repeatedly displays an emulator fallback such as `10.0.2.2`, especially after a server/network change.

## Evidence gates

Keep these claims separate:

```text
server listener
manifest served
APK served + SHA match
Watch reached manifest
Watch downloaded APK
installer opened
installed package/version verified
```

Mac `curl`, a built APK, or an empty `adb devices` list cannot prove the Watch gates.

## Reproduction pattern

1. An old Watch APK has a fixed manifest URL list ending in `10.0.2.2`.
2. A new APK adds `192.168.x.x` fallback, but the old APK is still installed.
3. The old updater cannot use the new fallback, so changing the server artifact does not bootstrap the device.
4. If the updater tries multiple URLs and only reports the last exception, the screen can still say `10.0.2.2` even when an earlier numeric candidate was attempted.

## Server/manifest checks

- Start with the last known-good server mode. Do not switch IPv4-only to IPv6/dual-stack without a target-path probe.
- Inspect the live process command and listener, not only the source script.
- Fetch the manifest using the same host form the client uses.
- If the client enforces same-host manifest/APK URLs, ensure the server derives `apkUrl` from the request `Host`; an IP manifest returning a `.local` APK URL is a client-side rejection.
- Download the live APK and compare its SHA-256 to the live manifest and fresh build.

## Mac/network checks

Use `networksetup -getinfo Wi-Fi`, `ifconfig`, `route -n get default`, and the active interface. Check for unexpected extra addresses, especially a `/32` alias overlapping the Watch's reported address. The `from /address` field in a Java connection exception identifies the local endpoint of the failing client; it is not sufficient evidence of device ownership. A Mac-local `ping` to an address assigned to the Mac only tests the Mac itself.

If a previous IPv4 configuration worked and the Watch fails after an IPv6 change, restore the previous launch command and listener before trying more changes. Treat removal of a privileged address alias as a separate, user-approved system-network action; do not enter credentials.

## Reporting rule

Never say "fixed" until the physical Watch shows the new version or provides equivalent verified package evidence. Report the last green gate and the first unverified gate explicitly. If bootstrap is blocked, say so and avoid presenting a direct APK download as if it were the agreed OTA deployment path.
