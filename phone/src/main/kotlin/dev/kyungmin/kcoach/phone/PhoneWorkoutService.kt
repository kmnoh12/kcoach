package dev.kyungmin.kcoach.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat

/**
 * Owns the phone-to-watch liveness loop independently from MainActivity.
 * The UI may disappear while an active workout continues, so heartbeat must
 * not be tied to Activity.onStart/onStop.
 */
class PhoneWorkoutService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val wearBridge by lazy { PhoneWearBridge(this) }
    private var heartbeatTicker: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        wearBridge.sendHello()
        startHeartbeat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getStringExtra(EXTRA_ACTION) == ACTION_PING) {
            wearBridge.sendHeartbeat()
        }
        val path = intent?.getStringExtra(EXTRA_PATH)
        val payload = intent?.getStringExtra(EXTRA_PAYLOAD)
        if (!path.isNullOrBlank() && payload != null) wearBridge.sendRaw(path, payload)
        return START_STICKY
    }

    override fun onDestroy() {
        heartbeatTicker?.let(handler::removeCallbacks)
        heartbeatTicker = null
        wearBridge.close()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startHeartbeat() {
        heartbeatTicker?.let(handler::removeCallbacks)
        val tick = object : Runnable {
            override fun run() {
                wearBridge.sendHeartbeat()
                handler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
            }
        }
        heartbeatTicker = tick
        handler.post(tick)
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "KCoach 운동 세션",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun buildNotification(): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_media_play)
        .setContentTitle("KCoach")
        .setContentText("워치 연결 상태를 유지하는 중")
        .setOngoing(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .build()

    companion object {
        const val ACTION_PING = "dev.kyungmin.kcoach.phone.PING"
        const val EXTRA_ACTION = "action"
        const val EXTRA_PATH = "path"
        const val EXTRA_PAYLOAD = "payload"
        private const val CHANNEL_ID = "kcoach_workout"
        private const val NOTIFICATION_ID = 4101
        private const val HEARTBEAT_INTERVAL_MS = 3_000L

        fun send(context: android.content.Context, path: String, payload: String) {
            context.startService(Intent(context, PhoneWorkoutService::class.java).apply {
                putExtra(EXTRA_PATH, path)
                putExtra(EXTRA_PAYLOAD, payload)
            })
        }
    }
}
