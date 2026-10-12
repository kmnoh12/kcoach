#!/usr/bin/env python3
"""Read-only physical-device preflight. Does not install, launch, stop or configure apps."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "dev.kyungmin.kcoach"


def call(adb, args):
    try:
        result = subprocess.run([str(adb), *args], capture_output=True, text=True, timeout=8)
        return result.stdout.strip() if result.returncode == 0 else None
    except (OSError, subprocess.TimeoutExpired):
        return None


def local_hash(path):
    if not path.is_file():
        return None
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(65536), b""):
            digest.update(block)
    return digest.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", type=Path, default=ROOT.parent / "안드로이드-에뮬레이터/android-sdk/platform-tools/adb")
    parser.add_argument("--phone-apk", type=Path, default=ROOT / "phone/build/outputs/apk/debug/phone-debug.apk")
    parser.add_argument("--wear-apk", type=Path, default=ROOT / "wear/build/outputs/apk/debug/wear-debug.apk")
    args = parser.parse_args()
    expected = {"phone": local_hash(args.phone_apk), "wear": local_hash(args.wear_apk)}
    listing = call(args.adb, ["devices", "-l"])
    report = {"observedAt": datetime.now(timezone.utc).isoformat(), "hardwareScenarios": "NOT_RUN",
              "dataLayerLink": "NOT_VERIFIED", "devices": [], "blockers": [], "expectedApkHashes": expected}
    if listing is None:
        report["blockers"].append("ADB inventory unavailable")
    for line in (listing or "").splitlines():
        fields = line.split()
        if len(fields) < 2 or fields[1] not in ("device", "offline", "unauthorized"):
            continue
        serial, state = fields[:2]
        device = {"serial": serial, "state": state, "preflightPassed": False}
        report["devices"].append(device)
        if state != "device":
            continue
        def shell(*values):
            return call(args.adb, ["-s", serial, "shell", *values])
        virtual = serial.startswith("emulator-") or shell("getprop", "ro.kernel.qemu") == "1"
        device["physical"] = not virtual
        if virtual:
            continue
        features = shell("pm", "list", "features")
        if features is None:
            device["role"] = "unknown"
            continue
        role = "wear" if "android.hardware.type.watch" in features else "phone"
        device["role"] = role
        device["model"] = shell("getprop", "ro.product.model")
        device["bluetoothEnabled"] = {"1": True, "0": False}.get(shell("settings", "get", "global", "bluetooth_on"))
        package = shell("dumpsys", "package", PACKAGE) or ""
        version = re.search(r"versionName=([^\s]+)", package)
        device["versionName"] = version.group(1) if version else None
        paths = shell("pm", "path", PACKAGE) or ""
        apks = [row.removeprefix("package:") for row in paths.splitlines() if row.startswith("package:")]
        actual = None
        if len(apks) == 1 and re.fullmatch(r"/data/app/[A-Za-z0-9/_~.+=-]+", apks[0]):
            output = shell("sha256sum", apks[0]) or ""
            match = re.match(r"[a-fA-F0-9]{64}", output)
            actual = match.group(0).lower() if match else None
        device["installedApkHash"] = actual
        device["matchesExpectedApk"] = actual == expected[role] if actual and expected[role] else None
        storage = shell("run-as", PACKAGE, "cat", "files/capture-storage.json")
        try:
            data = json.loads(storage) if storage else None
            device["storage"] = data if isinstance(data, dict) else {"status": "UNAVAILABLE"}
        except (ValueError, TypeError):
            device["storage"] = {"status": "UNAVAILABLE"}
        battery = shell("dumpsys", "battery") or ""
        level = re.search(r"^\s*level:\s*(\d+)", battery, re.MULTILINE)
        device["batteryLevel"] = int(level.group(1)) if level else None
        device["preflightPassed"] = bool(version and device["matchesExpectedApk"] and device["bluetoothEnabled"])
    for role in ("phone", "wear"):
        candidates = [d for d in report["devices"] if d.get("role") == role and d.get("physical")]
        if not candidates:
            report["blockers"].append(f"Physical {role} device missing")
        elif not any(d["preflightPassed"] for d in candidates):
            report["blockers"].append(f"{role}: app hash, authorization or Bluetooth check incomplete")
    report["preflightPassed"] = not report["blockers"]
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if report["preflightPassed"] else 2


if __name__ == "__main__":
    sys.exit(main())
