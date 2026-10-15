#!/usr/bin/env python3
"""Inspect APK DEX declarations required by MediaPipe JNI, Flogger and protobuf startup.

This checks release shrinking, not native initialization or pose accuracy.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess


def inspect(apk: Path, sdk: Path, java: Path, mapping: "Path | None" = None) -> dict:
    classpath = sdk / "cmdline-tools/latest/lib/apkanalyzer-classpath.jar"
    prefix = [str(java), f"-Dcom.android.sdklib.toolsdir={classpath.parents[1]}", "-cp", str(classpath),
              "com.android.tools.apk.analyzer.ApkAnalyzerCli", "dex", "code"]
    # Obfuscated classes such as Any are looked up by their original names.
    if mapping is not None:
        prefix += ["--proguard-mappings", str(mapping)]
    prefix += ["--class"]
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
        # Flogger discovers its enclosing class from real stack frames. These
        # methods must survive optimization, not merely have retrace metadata.
        "com.google.common.flogger.FluentLogger": {
            "static forEnclosingClass()": r"(?m)^\.method[^\n]*\bstatic\b[^\n]* forEnclosingClass\(\)Lcom/google/common/flogger/FluentLogger;\s*$",
        },
        # MediaPipe graph configs embed Any. Protobuf-lite reads its default
        # instance and fields reflectively; 0.7.7 shipped it as an empty abstract.
        "com.google.protobuf.Any": {
            "concrete class": r"(?m)^\.class(?![^\n]*\babstract\b)[^\n]*Lcom/google/protobuf/Any;\s*$",
            "static DEFAULT_INSTANCE": r"(?m)^\.field[^\n]*\bstatic\b[^\n]* DEFAULT_INSTANCE:Lcom/google/protobuf/Any;\s*$",
            "String typeUrl_": r"(?m)^\.field[^\n]* typeUrl_:Ljava/lang/String;\s*$",
        },
        "com.google.common.flogger.backend.system.StackBasedCallerFinder": {
            "findLoggingClass(Class)": r"(?m)^\.method[^\n]* findLoggingClass\(Ljava/lang/Class;\)Ljava/lang/String;\s*$",
        },
    }
    checks = {}
    for class_name, members in requirements.items():
        result = subprocess.run(prefix + [class_name, str(apk)], text=True, capture_output=True, timeout=60)
        checks[class_name] = {name: result.returncode == 0 and re.search(pattern, result.stdout) is not None
                              for name, pattern in members.items()}
    return {"apk": str(apk), "apkSha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
            "jniDeclarations": {name: members for name, members in checks.items() if name.startswith("com.google.mediapipe.")},
            "floggerStackDeclarations": {name: members for name, members in checks.items() if name.startswith("com.google.common.flogger.")},
            "protobufDeclarations": {name: members for name, members in checks.items() if name.startswith("com.google.protobuf.")},
            "allRequiredDeclarationsPresent": all(all(members.values()) for members in checks.values()),
            "nativeInitializationVerified": False, "poseDetectionVerified": False}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--sdk", type=Path, default=os.environ.get("ANDROID_SDK_ROOT"))
    parser.add_argument("--mapping", type=Path, help="R8 mapping.txt of this APK (needed for renamed classes)")
    parser.add_argument("--java", type=Path, default=Path(os.environ.get("JAVA_HOME", "")) / "bin/java")
    args = parser.parse_args()
    if args.sdk is None:
        parser.error("Set ANDROID_SDK_ROOT or pass --sdk")
    result = inspect(args.apk, args.sdk, args.java, args.mapping)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result["allRequiredDeclarationsPresent"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
