# Rest UI and phone↔watch synchronization gate

Use this reference when a phone-owned workout has an existing rest-control area and a Wear OS companion.

## Product/UI contract

- Keep the existing `휴식 시간 <N>초` control row, its dimensions, and its controls.
- Make the existing value decrement from `45초` to `44초` etc. while resting, then transition back to the next set/exercise.
- Do not add a second bottom “compact rest” bar unless the user explicitly asks for a new layout.
- A screenshot must confirm the original area is the one changing; a build alone is not enough.

## Verification sequence

1. Build and install both phone and wear APKs.
2. Start the emulator-only host relay and verify its port before launching the session.
3. Launch the phone-owned workout and verify watch `WORKING` state with exercise, set, reps, target, and weight.
4. Complete one set. Record the phone rest start time and the exact absolute `restEndAtMs` sent in the REST payload.
5. Verify phone countdown in the existing rest area and watch `RESTING` state using the same deadline. Do not infer REST delivery from a green connection badge or from START/SET success.
6. Wait through the deadline and verify both sides return to the next set/exercise. Check logcat for crashes/ANR and capture focused activity/UI state.
7. Only after this gate passes, prepare or transfer the real Watch APK. Emulator fake reps/heart rate prove transport/lifecycle only, not Watch8 sensor accuracy.

## Failure classification

- Phone rest visible, watch still working: REST payload/relay/receiver handling is broken; stop before upload.
- Watch rest visible but phone rest area is static: phone ticker/view binding is broken; stop before upload.
- Both enter rest but deadlines differ: protocol or clock/deadline propagation is broken; stop before upload.
- Both rest and return correctly but screen geometry changed: UX regression; restore the existing area before upload.
