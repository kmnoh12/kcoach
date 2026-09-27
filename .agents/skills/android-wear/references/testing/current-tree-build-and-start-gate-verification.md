# Current-tree build and start-gate verification

Use this reference when a handoff or stale report says the Android build is blocked.

## Build-tool rediscovery

1. Inspect the repository and project-local tooling before concluding that Gradle/JDK is unavailable. Check for `gradlew`, `tooling/`, `.gradle`, and known temporary tool directories.
2. Verify the executable itself, not just its parent directory. A leftover empty directory is not an installed tool.
3. Select the actual JDK home from the working toolchain and run Gradle with that explicit `JAVA_HOME`.
4. Run the smallest current-tree gate first, then expand:

```text
<gradle> :core:test --no-daemon --offline
<gradle> :phone:assembleDebug :wear:assembleDebug --no-daemon --offline
```

Record task output and test counts. Old XML reports are not evidence for the current source.

## Artifact/install/runtime separation

After assembly, hash both APKs. Install the exact paths on explicitly named targets. Do not guess the namespace: resolve the installed package from `pm list packages` if a guessed `pm path` is empty. Verify each form factor independently with `dumpsys package` (`versionName`, `versionCode`), then launch the declared Activity with `am start -W -n`. A successful launch and no fresh `FATAL EXCEPTION` prove only a smoke path; they do not prove sensor sampling, Data Layer transfer, repetition accuracy, or physical Watch behavior.

## Continuous two-second start gate

A preparation delay is not a posture gate. The deterministic gate tests should cover:

- stable candidate becomes ready only after the full 2,000 ms;
- an invalid gyro/acceleration sample resets the interval;
- a sample gap above the profile limit resets the interval;
- orientation/gravity drift resets the interval;
- capture and detector reset begin only after readiness.

The gate must clear its `stableSince` timestamp when stability breaks. Setting `stableSince` to the timestamp of an invalid sample can accidentally carry elapsed time across a break and falsely pass the two-second requirement.

## Reporting labels

Use separate statuses for source, core tests, APK assembly, emulator install, Activity smoke launch, emulator sensor/transport evidence, and physical Watch8 evidence. Emulator install or launch must never be reported as Watch8 sensor validation.
