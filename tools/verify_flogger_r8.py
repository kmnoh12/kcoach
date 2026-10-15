#!/usr/bin/env python3
"""Run the actual Flogger startup after R8 with the phone's keep rules.

This small classfile probe reproduces the POSE_INIT logging failure without an
APK build. It does not execute MediaPipe native code or verify a phone camera.
"""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile


PROBE = """import com.google.common.flogger.FluentLogger;
public class PoseLoggerProbe {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  public static void main(String[] args) {
    logger.atInfo().log("LOGGER_INIT_OK");
  }
}
"""


def run(args: list, stage: str) -> str:
    result = subprocess.run(list(map(str, args)), text=True, capture_output=True, timeout=60)
    output = result.stdout + result.stderr
    if result.returncode:
        raise RuntimeError(f"{stage} exited {result.returncode}\n{output}")
    return output


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--flogger-jar", type=Path, required=True)
    parser.add_argument("--backend-jar", type=Path, required=True)
    parser.add_argument("--java-home", type=Path, default=os.environ.get("JAVA_HOME"), required="JAVA_HOME" not in os.environ)
    parser.add_argument("--r8-jar", type=Path, required=True)
    parser.add_argument("--rules", type=Path, default=Path(__file__).resolve().parents[1] / "phone/proguard-rules.pro")
    args = parser.parse_args()
    jars = [args.flogger_jar.resolve(), args.backend_jar.resolve()]
    jdk = args.java_home.resolve()
    with tempfile.TemporaryDirectory(prefix="kcoach-flogger-") as temporary:
        directory = Path(temporary)
        source = directory / "PoseLoggerProbe.java"
        source.write_text(PROBE)
        run([jdk / "bin/javac", "--release", "11", "-cp", os.pathsep.join(map(str, jars)), "-d", directory, source], "javac")
        original = run([jdk / "bin/java", "-cp", os.pathsep.join(map(str, [directory, *jars])), "PoseLoggerProbe"], "unshrunk startup")
        if "LOGGER_INIT_OK" not in original:
            raise RuntimeError("Unshrunk positive control did not log startup success")
        probe = directory / "probe.jar"
        run([jdk / "bin/jar", "cf", probe, "-C", directory, "PoseLoggerProbe.class"], "jar")
        rules = directory / "probe.pro"
        rules.write_text("""-keep class PoseLoggerProbe { public static void main(java.lang.String[]); }
-keep class com.google.common.flogger.backend.system.DefaultPlatform { public <init>(); }
-dontwarn com.google.errorprone.**
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**
-dontwarn sun.misc.**
""")
        output = directory / "shrunk.jar"
        run([jdk / "bin/java", "-cp", args.r8_jar.resolve(), "com.android.tools.r8.R8", "--release", "--classfile",
             "--lib", jdk, "--pg-conf", rules, "--pg-conf", args.rules.resolve(), "--output", output, probe, *jars], "R8")
        result = run([jdk / "bin/java", "-cp", output, "PoseLoggerProbe"], "shrunk startup")
        if "LOGGER_INIT_OK" not in result:
            raise RuntimeError("Shrunk startup did not log success")
        print("PASS: Flogger starts before and after R8 with the phone's keep rules.")
        print("Not verified: MediaPipe native initialization or device pose detection.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
