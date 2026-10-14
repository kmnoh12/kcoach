package dev.kyungmin.kcoach.phone

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
import java.util.IdentityHashMap

/** Only camera/model startup exceptions: no image, landmark, account or workout payload. */
internal object CameraInitializationFailure {
    fun report(phase: String, failure: Throwable): String {
        require(phase in setOf("CAMERA_BIND", "POSE_INIT", "FRAME_PROCESSING"))
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        val causes = JSONArray()
        var error: Throwable? = failure
        while (error != null && causes.length() < 6 && seen.add(error)) {
            val detail = error.message.orEmpty().replace(Regex("[\\r\\n\\t]+"), " ")
                .replace(Regex("(?i)(bearer\\s+|access_token[=: ]+|refresh_token[=: ]+)[A-Za-z0-9._~+/-]+"), "$1<redacted>")
                .take(500)
            causes.put(JSONObject().put("type", error.javaClass.name).put("detail", detail))
            error = error.cause
        }
        return JSONObject().put("version", 1).put("app_version", BuildConfig.VERSION_NAME)
            .put("phase", phase).put("android_sdk", Build.VERSION.SDK_INT)
            .put("abis", JSONArray(Build.SUPPORTED_ABIS.toList())).put("causes", causes).toString()
    }
    fun save(context: Context, phase: String, failure: Throwable): String = report(phase, failure).also {
        // A small local last-error record also survives closing the camera screen.
        context.getSharedPreferences("camera-initialization", Context.MODE_PRIVATE).edit().putString("last_error", it).apply()
    }
}
