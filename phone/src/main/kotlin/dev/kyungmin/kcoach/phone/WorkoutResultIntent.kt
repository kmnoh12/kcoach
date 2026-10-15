package dev.kyungmin.kcoach.phone

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.util.UUID

/** Phone-local entry point for an explicit "view this finished workout" user action. */
internal object WorkoutResultIntent {
    const val ACTION = "dev.kyungmin.kcoach.action.SHOW_WORKOUT_RESULT"
    const val EXTRA_SESSION_ID = "workoutResultSessionId"
    private const val EXTRA_REQUEST_ID = "workoutResultRequestId"
    private val ID = Regex("[A-Za-z0-9._-]{1,160}")

    fun create(context: Context, sessionId: String): Intent {
        require(ID.matches(sessionId))
        return Intent(context, MainActivity::class.java).setAction(ACTION).putExtra(EXTRA_SESSION_ID, sessionId)
            .putExtra(EXTRA_REQUEST_ID, UUID.randomUUID().toString())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }

    /** RemoteActivityHelper forwards only VIEW/categories/data, so the IDs must be in the URI. */
    fun createRemoteView(sessionId: String): Intent {
        require(ID.matches(sessionId))
        val uri = Uri.Builder().scheme("kcoach").authority("workout").path("/result")
            .appendQueryParameter("session_id", sessionId)
            .appendQueryParameter("request_id", UUID.randomUUID().toString()).build()
        return Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
    }

    private fun remoteUri(intent: Intent): Uri? = intent.data?.takeIf {
        intent.action == Intent.ACTION_VIEW && it.scheme == "kcoach" && it.host == "workout" && it.path == "/result"
    }

    fun isRequest(intent: Intent): Boolean = intent.action == ACTION || remoteUri(intent) != null

    fun sessionId(intent: Intent): String? = runCatching {
        if (intent.action == ACTION) intent.getStringExtra(EXTRA_SESSION_ID)
        else remoteUri(intent)?.getQueryParameters("session_id")?.singleOrNull()
    }.getOrNull()?.takeIf(ID::matches)

    /** Saved UI state can ignore a replay without suppressing a new user request for the same result. */
    fun requestKey(intent: Intent): String? = if (!isRequest(intent)) null else
        runCatching {
            if (intent.action == ACTION) intent.getStringExtra(EXTRA_REQUEST_ID)
            else remoteUri(intent)?.getQueryParameters("request_id")?.singleOrNull()
        }.getOrNull()?.takeIf(ID::matches)
            ?: "session:${sessionId(intent).orEmpty()}"
}
