# KCoach active-workout reproduction reference

## Observed anti-pattern

In `phone/src/main/kotlin/dev/kyungmin/kcoach/phone/MainActivity.kt`, the active workout click paths called `showActiveWorkout()` for local edits. Before the fix, this rebuilt the active page and its child view hierarchy, including the exercise animation, on every set add/delete/completion-related update. `exerciseAnimationView()` also decoded the drawable through `ImageDecoder` each time it was constructed.

`PhoneWearBridge` used a background thread for transport, so it was not sufficient to blame synchronous socket I/O without tracing. However, repeated UI-triggered sends and object/thread creation still belonged in the investigation.

## Partial-refresh pattern

The implemented direction was:

- Add an `ActiveWorkoutUi` holder for the active set-row container and summary/status TextViews.
- Let `showActiveWorkout()` return through `refreshActiveWorkoutUi()` when the same `ActiveWorkout` is already on the active screen and no navigation rebuild is requested.
- Rebuild only the set-row container and update target/performed/summary/rest-duration text.
- Use `forceRebuild = true` for a genuine exercise transition.
- Avoid duplicate `wearBridge.sendSet()` calls from a local refresh.
- Cache exercise animation `Drawable.ConstantState` values and create a new drawable from the cached state when rebuilding for navigation.

## Verification recipe

1. Build `:phone:assembleDebug` with the project JDK/Gradle wrapper or the repository's configured toolchain.
2. Install only the phone APK on `emulator-5554` when watch installation is deferred.
3. Navigate into a routine and start a workout; distinguish the intended rest screen after completing the current set from active-screen editing.
4. On the active screen, exercise add set, delete set, complete, and uncomplete separately.
5. Clear logcat before a repeatable burst and inspect `Choreographer`, `Skipped`, `ANR`, crashes, and `dumpsys gfxinfo dev.kyungmin.kcoach`.
6. Capture a screenshot/state after each action. Treat a stale UIAutomator tree during a transition as inconclusive and recapture after the screen settles.

## Important limitation

A successful compile and APK install are not equivalent to a smooth-interaction verification. The final report must name which of the four interaction classes were actually exercised on the newly installed artifact and which metrics were collected.
