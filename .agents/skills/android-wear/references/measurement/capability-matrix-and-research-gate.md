# Capability Matrix and Research Gate

Use this reference before changing 워치짐 exercise recognition code.

## 1. No-code gate

Do not implement from a broad instruction such as “count exercises and analyze form.” First produce:

1. the canonical inventory of the user's exercise variants;
2. a source-grounded method and failure-mode survey;
3. a protocol-level capability card for every variant;
4. an output contract with explicit `N/A` fields;
5. acceptance criteria and negative cases;
6. an implementation plan that names the files/models to change.

Only then start source edits. If the capability contract changes, stop implementation and revise the contract first.

## 2. Capability key

Capability is not an exercise-name property. Key it by:

```text
exercise_id + variant + measurement_protocol + watch_side + wear_condition
+ grip/contact_condition + calibration_state
```

A standard exercise protocol and a constrained proxy protocol are different products. Example: standard leg press is D/manual; a documented hand-on-knee proxy may be conditional C/beta, but it reports a wrist/hand proxy rather than true leg kinematics.

## 3. Four tiers and validation status

| Tier | Meaning | Allowed default output |
|---|---|---|
| A | Wrist-direct motion; automatic count and wrist-motion indices can be reliable after validation | rep count, duration, wrist angular speed, normalized wrist motion range, trajectory/tempo consistency, signal quality |
| B | Wrist is coupled to a bar/handle; count may work but exercise/implement meaning is limited | count, duration, confidence, wrist trajectory consistency; do not claim bar displacement, true load speed, touch point, joint alignment |
| C | Weak indirect signal; personal calibration and fixed protocol required | personalized beta count, duration, confidence, cadence/signal similarity; no objective top/bottom ROM or posture claim |
| D | Wrist does not observe the primary motion | session/time/heart rate; manual phone count; automatic rep/form output is N/A |

Keep a separate validation label: `UNVALIDATED`, `REPLAY_VALIDATED`, `EMULATOR_TRANSPORT_ONLY`, or `WATCH8_VALIDATED`. A synthetic pulse or emulator relay can prove detector plumbing or transport, never exercise accuracy.

## 4. Protocol and calibration candidates

Investigate, do not assume, methods such as orientation-invariant accel/gyro magnitudes, gravity-relative components, periodicity/autocorrelation, change-point/set-boundary detection, template matching/DTW, phase/state machines, and personalized cadence envelopes. Calibration can include a labelled fixed-repetition set, rest posture, watch orientation, grip/contact condition, and expected tempo. Calibration improves inference only within its recorded protocol; it cannot prove anatomy that the watch cannot observe.

Treat end gestures (for example, standing with arms down after pull-ups) as a multi-signal set-boundary cue combined with quiet time and phase termination, not as a sole proof of completed repetitions. If the setup/contact/orientation gate fails, lower confidence and offer manual input.

## 5. Evidence source roles

- Papers, standards, and public datasets: method validity, sensors, labels, metrics, limitations.
- GitHub/Gitee: reproducible implementations, preprocessing, state machines, device constraints, test fixtures.
- Reddit and English/Chinese practitioner communities: practical failure modes, wear placement, grip/machine variation, battery and sampling issues.
- Official Android/Wear/Watch documentation: sensor and lifecycle constraints.

Record canonical URL, source type, exercise/protocol, sensor placement, algorithm, labels, result metric, and limitation. Never transfer a community claim or a paper's benchmark directly into 워치짐 accuracy.

## 6. Capability-card minimum fields

```yaml
exercise_id:
variant:
measurement_protocol:
tier:
validation_status:
count_mode:
watch_side:
wear_condition:
required_setup:
calibration:
supported_metrics:
unsupported_metrics:
proxy_metrics:
failure_conditions:
fallback:
validation_method:
acceptance_criteria:
```

## 7. Execution gate

Work in dependency batches: capability contract → result semantics and fallback → start/stop lifecycle → fresh build/install/emulator → physical Watch8 → labelled accuracy → OTA/evidence report. Within a batch, edit shared models and UI sequentially; parallelize only independent reads or tests. Mark a task complete only with current-source evidence.
