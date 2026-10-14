package dev.kyungmin.kcoach.phone

import android.content.Context
import android.os.Handler
import android.os.Looper

internal object AutomaticWorkoutReview {
    fun finished(context: Context, sessionId: String) {
        val app = context.applicationContext
        val handler = Handler(Looper.getMainLooper())
        val started = android.os.SystemClock.elapsedRealtime()
        var modelsRequested = false
        fun prepare() {
            val auth = ChatGptAuth.get(app)
            if (auth.state.value.loading || auth.state.value.busy || auth.state.value.connected && auth.state.value.planAllowed && !auth.modelSettings.value.loaded) {
                if (!modelsRequested && !auth.state.value.loading && !auth.state.value.busy && auth.state.value.connected && auth.state.value.planAllowed) { modelsRequested = true; auth.loadModels() }
                if (android.os.SystemClock.elapsedRealtime() - started < 60_000) handler.postDelayed({ prepare() }, 500)
                return
            }
            runCatching {
                val accountId = auth.state.value.activeProfileId ?: return@runCatching
                val settings = AiTaskSettings(app)
                val value = settings.read(accountId)
                if (!value.reviewEnabled || !value.automaticReview || !auth.state.value.connected || !auth.state.value.planAllowed) return@runCatching
                auth.selectedWorkRequestOptions() // Freeze only when configured; setting changes do not run work.
                val store = AiCoachStore(app)
                if (store.profile(accountId) == null) return@runCatching
                if (sessionId !in store.sessionIds(accountId)) {
                    if (!value.linkNewWorkouts) return@runCatching
                    store.linkSessions(accountId, listOf(sessionId)) // Explicit future-record association consent.
                }
                if (settings.claimReviewEvent(accountId, sessionId)) WorkoutReviewController.get(app).automatic(accountId, sessionId)
            }
        }
        handler.post { prepare() }
    }
}
