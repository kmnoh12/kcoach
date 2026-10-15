package dev.kyungmin.kcoach.wear

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.wear.remote.interactions.RemoteActivityHelper
import java.util.UUID

internal object WatchPhoneResult {
    fun intent(sessionId: String, requestId: String = UUID.randomUUID().toString()): Intent {
        require(WatchHomeStore.validSession(sessionId))
        require(UUID.fromString(requestId).toString() == requestId)
        return Intent(Intent.ACTION_VIEW).addCategory(Intent.CATEGORY_BROWSABLE).setData(
            Uri.Builder().scheme("kcoach").authority("workout").appendPath("result")
                .appendQueryParameter("session_id", sessionId).appendQueryParameter("request_id", requestId).build())
    }
    /** Success means the remote-launch request completed, not that a phone screen was observed. */
    fun open(context: Context, intent: Intent, done: (Boolean) -> Unit) {
        val executor = ContextCompat.getMainExecutor(context)
        runCatching { RemoteActivityHelper(context.applicationContext, executor).startRemoteActivity(intent) }
            .onSuccess { future -> future.addListener({ done(runCatching { future.get(); true }.getOrDefault(false)) }, executor) }
            .onFailure { done(false) }
    }
}
