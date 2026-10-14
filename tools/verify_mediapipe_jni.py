#!/usr/bin/env python3
"""Inspect real APK DEX declarations required by MediaPipe 0.10.35 JNI.

This checks release shrinking, not native initialization or pose accuracy.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess


def inspect(apk: Path, sdk: Path, java: Path) -> dict:
    classpath = sdk / "cmdline-tools/latest/lib/apkanalyzer-classpath.jar"
    prefix = [str(java), f"-Dcom.android.sdklib.toolsdir={classpath.parents[1]}", "-cp", str(classpath),
              "com.android.tools.apk.analyzer.ApkAnalyzerCli", "dex", "code", "--class"]
    requirements = {
        "com.google.mediapipe.framework.MediaPipeException": {
            "constructor(I,byte[])": r"(?m)^\.method[^\n]* <init>\(I\[B\)V\s*$",
        },
        "com.google.mediapipe.framework.Packet": {
            "static create(long)": r"(?m)^\.method[^\n]*\bstatic\b[^\n]* create\(J\)Lcom/google/mediapipe/framework/Packet;\s*$",
        },
        "com.google.mediapipe.framework.ProtoUtil$SerializedMessage": {
            "String typeName": r"(?m)^\.field[^\n]* typeName:Ljava/lang/String;\s*$",
            "byte[] value": r"(?m)^\.field[^\n]* value:\[B\s*$",
        },
    }
    checks = {}
    for class_name, members in requirements.items():
        result = subprocess.run(prefix + [class_name, str(apk)], text=True, capture_output=True, timeout=60)
        checks[class_name] = {name: result.returncode == 0 and re.search(pattern, result.stdout) is not None
                              for name, pattern in members.items()}
    return {"apk": str(apk), "apkSha256": hashlib.sha256(apk.read_bytes()).hexdigest(), "jniDeclarations": checks,
            "allRequiredDeclarationsPresent": all(all(members.values()) for members in checks.values()),
            "nativeInitializationVerified": False, "poseDetectionVerified": False}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--sdk", type=Path, default=os.environ.get("ANDROID_SDK_ROOT"))
    parser.add_argument("--java", type=Path, default=Path(os.environ.get("JAVA_HOME", "")) / "bin/java")
    args = parser.parse_args()
    if args.sdk is None:
        parser.error("Set ANDROID_SDK_ROOT or pass --sdk")
    result = inspect(args.apk, args.sdk, args.java)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result["allRequiredDeclarationsPresent"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
