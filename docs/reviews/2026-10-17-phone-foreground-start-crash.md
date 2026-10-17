# Phone foreground-start deadline crash

The user-supplied diagnostics ZIP contains `RemoteServiceException$ForegroundServiceDidNotStartInTimeException`, with multiple Java-crash records in Android exit history. The export identifies Samsung SM-S938N / Android 17 / API 37. This recorded exception is a foreground-service lifecycle failure, not an OutOfMemoryError or cache diagnosis. Earlier rest-screen exits have no retained exception stacks in this ZIP, so their causes remain unconfirmed.

The last exception predates the version-stamped diagnostics update. Its crash version is not recorded. The retained 0.2.6 R8 mapping resolves the launch stack through MainActivity.endWorkout → PhoneWorkoutService.retryPending → ContextCompat.startForegroundService, consistent with the recorded ROOT/IDLE state. Treat that mapping match separately from independently verified crash-version metadata.

PhoneWorkoutService previously called startForeground only in onCreate, after capture recovery. A new foreground-start request can reach the same instance after stopForeground/stopSelf but before destruction; onCreate does not run again, leaving that request unacknowledged. Capture recovery also performed file/lock work before initial promotion.

The fix promotes the service before capture recovery and at the start of every onStartCommand. Reaccepted requests reset the idle grace clock and rearm heartbeat/rest timers, so a re-promoted delivery-only service still stops after its existing bounded retry period. Durable STOP commands and workout ownership are preserved.

Regression seam: create the real PhoneWorkoutService, advance it to idle shutdown, deliver a real durable STOP through PhoneWorkoutService.send to the existing instance, then assert foreground promotion and eventual bounded shutdown. This failed before the patch; all six IdleServiceTest cases pass after it, including paused-session liveness and durable terminal-command behavior. Robolectric models the lifecycle state; it does not reproduce Android 17's system timeout or verify the physical phone.

Phone-only 0.2.8 / code 47. Watch remains 0.2.3 / code 39. Physical confirmation and any separate rest-screen crash diagnosis require subsequent device evidence.
