# Resume a paused phone↔Wear runtime verification

Use this recipe when a phone/Wear task resumes after a context handoff, compaction, or several idle days. Historical summaries are useful for locating the last claimed gate, but they are not current evidence.

## 1. Reconstruct, then invalidate stale claims

- Search the prior session for the last verified gate and the first explicitly pending gate.
- Treat every old build, install, device, relay, and runtime claim as historical until rechecked against the live tree and live targets.
- Do not restart from a vague product goal; resume at the smallest unverified gate.

## 2. Re-discover the current build path

- Inspect the current source tree and module configuration before editing.
- If the normal `java`, `gradle`, or wrapper command is missing, search the project workspace for its local tooling/cache before declaring the build blocked. Use explicit `JAVA_HOME`/Android SDK paths for the run; do not turn a transient command-path problem into a product conclusion.
- Run the current-tree shared tests first, then both phone/Wear `assembleDebug` and `lintDebug`. Record task-level output, not only an exit code.

## 3. Rebuild and prove artifact identity

- Locate the newly assembled phone and Wear APKs.
- Read version code/name and calculate SHA-256 for each artifact.
- Install the exact fresh APK on the intended phone and Wear targets.
- Verify with `adb devices -l`, package path, installed version code/name, and a pulled installed `base.apk` hash. An old installed app or a successful launch is not artifact evidence.

## 4. Re-establish the emulator lane explicitly

- Keep a host relay separate from `adb forward`; check the relay listener with `lsof` and confirm both app services/processes are connected before diagnosing UI or transport behavior.
- Clear target logcat, force-stop both apps, and launch them fresh.
- Check `dumpsys activity`/focused activity, process IDs, UI hierarchy, screenshot, and logcat together. If the UI dump and screenshot disagree, refresh the dump and use the screenshot plus focused activity and event logs to establish what is actually visible; do not infer an app crash from a stale hierarchy.

## 5. Exercise a minimal end-to-end scenario

Use a deterministic short routine and collect the event chain, not just the final screen:

```text
session start
→ phone↔Wear connected/live state
→ capture asset received
→ capture validated
→ analysis/result persisted
→ idempotent ACK sent
→ SET_COMPLETE_RECEIVED
→ SET_STATE_MUTATED
→ REST_COMMAND_SENT
→ active UI updated
```

A relay connection proves wire reachability only. The capture is green only when manifest/bytes/KCS/hash validation, persistence, analysis, and ACK evidence are present.

## 6. Test recovery, not just first launch

- Force-stop and relaunch the phone during an active or rest state.
- Confirm the same workout/session, set progress, completion state, and pairing status return on screen.
- Read the app's durable active-session snapshot/preferences where available, and check for `FATAL EXCEPTION`/`AndroidRuntime` markers after relaunch.
- Keep set completion separate from full session shutdown; verify that post-set rest survives, while STOP/END/COMPLETE later tears down sensors, notification, watchdog, and service.

## 7. Handoff to the physical gate honestly

- Label emulator/relay captures as `emulator transport/lifecycle only`; a synthetic `Watch8 connected` label, fake heart rate, or generated repetitions is not physical Watch evidence and does not establish recognition accuracy.
- Only after the emulator gates are green ask the user for the real Watch8 action: connect the physical target through the agreed delivery/debug path and perform one protocol-defined set. Do not ask for APK re-downloads or data resets when the next gate is device evidence.
- Keep `source`, `build`, `artifact`, `installed`, `runtime`, `capture`, `recovery`, and `physical sensor` claims separate in the final report.
