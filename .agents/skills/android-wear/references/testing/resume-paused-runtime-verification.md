# Resume only the requested phone↔Wear work

A handoff, context compaction, or idle period is not a request to repeat verification. Follow the current user scope and AGENTS.md over older checklists. Do not create or resume a broad goal from this recipe.

## Resume from recorded progress

- Read the last result and the specific pending task. Keep earlier successful checks as recorded evidence; do not rerun them solely because the session resumed.
- Distinguish historical build/device claims from current runtime observations without automatically rebuilding, reinstalling, or invalidating completed work.
- Inspect only the source/configuration needed for the requested change. Preserve other agents' dirty changes.

## Choose one narrow check

- Use one test class/file tied to the change, or a single-module compile. Do not run both by habit.
- Phone data tests can inherit the full Compose task through Gradle dependencies. Exclude `:phone:testDebugCompose` when running a narrow data test.
- Do not run full-module tests, full builds, lint, UI/screenshot suites, clean builds, forced nonincremental builds, or `--max-workers=1` unless the user requests that scope. Completion or release preparation is not an automatic exception.
- Redirect long output to a log file and inspect failing lines only.
- If local Java/Gradle is not on PATH, use the project's known tool paths; do not broaden verification to diagnose a command-path issue.
- If the same error repeats three times or there is no progress for about 15 minutes, stop and report the error and attempted fix. Do not expand the test scope.

## Artifact and runtime work are conditional

- Build an APK only for an authorized delivery/install task. Reuse valid recorded build results where applicable; do not rebuild both modules for a one-module change.
- For an authorized release, verify the requested artifact's version, signature and published hash. This does not require a full test/lint gate.
- Install, pull installed APKs, reconnect relays, force-stop apps, clear logs, inspect screens, or exercise recovery only when the user requested that device/runtime work. A code handoff alone authorizes none of these actions.
- If runtime verification is requested, choose the smallest scenario addressing that issue. Do not automatically append a full session, recovery matrix or physical Watch8 gate.
- Honor explicit exclusions such as no phone screen checks, no omission audit and no device resets. Do not ask for downloads or resets merely to repeat earlier evidence.

## Report the evidence actually obtained

Keep source, target test/compile, artifact, installed runtime, capture integrity and physical sensor accuracy as separate claims. Emulator data is not physical Watch8 evidence. State unverified scope without turning it into an instruction to execute every remaining gate.

Handoffs record completed work and the next requested action. Never direct the next agent to rerun the full gate or all previous checks.
