package dev.kyungmin.kcoach.phone

import android.content.Context

data class AiTaskPreferences(val automaticReview: Boolean = false, val linkNewWorkouts: Boolean = false,
    val autoApplyValidatedEngine: Boolean = false, val chatEnabled: Boolean = true, val reviewEnabled: Boolean = true, val calibrationEnabled: Boolean = true)

internal class AiTaskSettings(context: Context) {
    private val prefs = context.getSharedPreferences("ai-task-settings", Context.MODE_PRIVATE)
    fun read(accountId: String): AiTaskPreferences = AiTaskPreferences(prefs.getBoolean("$accountId:review", false),
        prefs.getBoolean("$accountId:link", false), prefs.getBoolean("$accountId:engine", false), prefs.getBoolean("$accountId:chat-enabled", true), prefs.getBoolean("$accountId:review-enabled", true), prefs.getBoolean("$accountId:calibration-enabled", true))
    fun save(accountId: String, value: AiTaskPreferences) {
        require(accountId.isNotBlank())
        check(prefs.edit().putBoolean("$accountId:review", value.automaticReview).putBoolean("$accountId:link", value.linkNewWorkouts)
            .putBoolean("$accountId:engine", value.autoApplyValidatedEngine).putBoolean("$accountId:chat-enabled", value.chatEnabled)
            .putBoolean("$accountId:review-enabled", value.reviewEnabled).putBoolean("$accountId:calibration-enabled", value.calibrationEnabled).commit())
    }
    /** One finish event, not every late revision. Interrupted/failed requests require a manual action. */
    fun claimReviewEvent(accountId: String, sessionId: String): Boolean = synchronized(lock) {
        val key = "$accountId:finish:$sessionId"
        if (prefs.contains(key)) false else { check(prefs.edit().putBoolean(key, true).commit()); true }
    }
    companion object { private val lock = Any() }
}
