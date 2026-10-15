package dev.kyungmin.kcoach.phone

/** Retains the already-stopped attempt until its final metadata is durably committed. */
internal class CalibrationCaptureFinalizer(private val store: CalibrationCaptureStore) {
    data class Pending(val capture: CalibrationCapture, val videoComplete: Boolean, val reason: String?, val motionEndCameraNs: Long? = null,
        val performance: List<String> = emptyList())
    @Volatile var pending: Pending? = null
        private set

    @Synchronized fun prepare(capture: CalibrationCapture, videoComplete: Boolean, reason: String?, motionEndCameraNs: Long? = null,
        performance: List<String> = emptyList()) {
        check(pending == null)
        pending = Pending(capture, videoComplete, reason, motionEndCameraNs, performance)
    }

    @Synchronized fun save(): Result<CalibrationCapture> {
        val attempt = pending ?: return Result.failure(IllegalStateException("No stopped capture to save"))
        val result = store.finish(attempt.capture.id, attempt.videoComplete, attempt.reason, attempt.motionEndCameraNs)
            .mapCatching { saved -> store.savePerformance(saved.id, attempt.performance).getOrThrow(); saved }
        if (result.isSuccess) pending = null
        return result
    }
}

internal fun captureStorageFull(error: Throwable): Boolean {
    val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
    var cause: Throwable? = error
    while (cause != null && seen.size < 6 && seen.add(cause)) {
        if (cause is android.system.ErrnoException && cause.errno == android.system.OsConstants.ENOSPC ||
            cause.message.orEmpty().contains(Regex("(?i)\\bENOSPC\\b|no space left on device"))) return true
        cause = cause.cause
    }
    return false
}

internal fun captureSaveFailureText(error: Throwable): String = if (captureStorageFull(error))
    "저장 공간이 부족해서 종료 상태를 저장하지 못했어요. 공간을 확보한 뒤 저장을 다시 시도해 주세요."
else "촬영 종료 상태를 저장하지 못했어요. 저장 상태를 확인한 뒤 다시 시도해 주세요."
