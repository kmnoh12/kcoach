# Contract enforcement pattern

Use this checklist when converting a smartwatch exercise-observability design into a shared Android/core implementation.

## Metadata contract

Each row must identify:

```text
exercise_id
variant_id
protocol_id
capability_tier
watch_side / wear_condition
grip / contact_condition
count_mode
supported_metrics
unsupported_metrics
fallback
required_setup
validation_method
```

Standard and setup-assisted variants are separate rows. A proxy variant must not upgrade the standard exercise.

## Output contract

Use a single result-boundary policy so callers cannot bypass honesty through a different UI or serializer.

- A: validated wrist-direct metrics only.
- B: count plus limited wrist metrics; no trajectory/form-like feedback unless explicitly supported.
- C: beta count only under calibration/setup conditions; expose a manual fallback.
- D: manual source; remove automatic reps, confidence, wrist range/speed, and form-like scores.

Represent wrist observations with explicit names. Keep deprecated form fields only when wire compatibility requires them, and write them empty/null for new analysis.

## Tests

Minimum contract tests:

1. standard matrix size and unique IDs;
2. every manual-required lower-body path remains manual;
3. every conditional proxy resolves only with its explicit variant;
4. pull-up does not claim chin clearance or strictness;
5. D sanitizer removes automatic counts and form-like outputs;
6. B sanitizer removes unsupported feedback;
7. C metadata names beta mode and manual fallback;
8. A exposes only the permitted wrist metrics.

Run the focused core suite first, then the complete multi-module suite. Report emulator/lifecycle tests and Watch8 raw-sensor accuracy as separate evidence classes.

## Evidence wording

Say “contract implemented and tests passed” when the shared policy is verified. Do not say “exercise accuracy validated” until labeled Watch8 raw IMU data and a per-variant acceptance report exist.
