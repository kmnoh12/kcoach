package dev.kyungmin.kcoach.wear

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import dev.kyungmin.kcoach.core.ProbeWire
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.ArrayDeque

/** Live numbers the probe screen polls; the service is the only writer. */
object ProbeStatus {
    @Volatile var running = false
    @Volatile var connected = false
    @Volatile var accelHz = 0.0
    @Volatile var gyroHz = 0.0
    @Volatile var accelMinDelayUs = 0
    @Volatile var gyroMinDelayUs = 0
    @Volatile var sentSamples = 0L
    @Volatile var bufferedSamples = 0
    @Volatile var droppedSamples = 0L
    @Volatile var message = ""
}

/**
 * Measurement probe: streams raw accelerometer + gyroscope to the phone over a
 * Data Layer channel. Samples stay buffered on the watch while the phone is
 * unreachable and are sent once the channel reopens.
 */
class ProbeService : Service(), SensorEventListener {
    private lateinit var sensorManager: SensorManager
    private lateinit var powerManager: PowerManager
    private var wakeLock: PowerManager.WakeLock? = null
    private val buffer = ArrayDeque<ProbeWire.Sample>()
    private val writeLock = Any()
    @Volatile private var stopping = false
    private var senderThread: Thread? = null

    private var accelWindowStart = 0L
    private var accelWindowCount = 0
    private var gyroWindowStart = 0L
    private var gyroWindowCount = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(SensorManager::class.java)
        powerManager = getSystemService(PowerManager::class.java)
        startInForeground()
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "KCoach:Probe").apply {
            setReferenceCounted(false)
            acquire(60 * 60 * 1000L)
        }
        ProbeStatus.apply {
            running = true
            connected = false
            sentSamples = 0
            droppedSamples = 0
            message = "센서 시작"
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            ProbeStatus.accelMinDelayUs = it.minDelay
            sensorManager.registerListener(this, it, SAMPLING_PERIOD_US, 0)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
            ProbeStatus.gyroMinDelayUs = it.minDelay
            sensorManager.registerListener(this, it, SAMPLING_PERIOD_US, 0)
        }
        senderThread = Thread(::sendLoop, "kcoach-probe-sender").also { it.start() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        stopping = true
        sensorManager.unregisterListener(this)
        senderThread?.interrupt()
        wakeLock?.let { if (it.isHeld) it.release() }
        ProbeStatus.running = false
        ProbeStatus.connected = false
        super.onDestroy()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onSensorChanged(event: SensorEvent) {
        val sensor = when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> ProbeWire.SENSOR_ACCEL
            Sensor.TYPE_GYROSCOPE -> ProbeWire.SENSOR_GYRO
            else -> return
        }
        countRate(sensor, event.timestamp)
        synchronized(buffer) {
            if (buffer.size >= MAX_BUFFERED) {
                buffer.removeFirst()
                ProbeStatus.droppedSamples++
            }
            buffer.addLast(ProbeWire.Sample(sensor, event.timestamp, event.values[0], event.values[1], event.values[2]))
            ProbeStatus.bufferedSamples = buffer.size
        }
    }

    private fun countRate(sensor: Int, timestamp: Long) {
        if (sensor == ProbeWire.SENSOR_ACCEL) {
            if (accelWindowStart == 0L) accelWindowStart = timestamp
            accelWindowCount++
            val span = timestamp - accelWindowStart
            if (span >= 1_000_000_000L) {
                ProbeStatus.accelHz = (accelWindowCount - 1) * 1e9 / span
                accelWindowStart = timestamp
                accelWindowCount = 1
            }
        } else {
            if (gyroWindowStart == 0L) gyroWindowStart = timestamp
            gyroWindowCount++
            val span = timestamp - gyroWindowStart
            if (span >= 1_000_000_000L) {
                ProbeStatus.gyroHz = (gyroWindowCount - 1) * 1e9 / span
                gyroWindowStart = timestamp
                gyroWindowCount = 1
            }
        }
    }

    private fun sendLoop() {
        val channelClient = Wearable.getChannelClient(this)
        var seq = 0
        while (!stopping) {
            var channel: ChannelClient.Channel? = null
            try {
                ProbeStatus.message = "폰 찾는 중"
                val node = Tasks.await(Wearable.getNodeClient(this).connectedNodes)
                    .sortedByDescending { it.isNearby }
                    .firstOrNull() ?: throw IllegalStateException("연결된 폰 없음")
                channel = Tasks.await(channelClient.openChannel(node.id, ProbeWire.CHANNEL_PATH))
                val out = DataOutputStream(BufferedOutputStream(Tasks.await(channelClient.getOutputStream(channel))))
                val input = DataInputStream(Tasks.await(channelClient.getInputStream(channel)))
                ProbeStatus.connected = true
                ProbeStatus.message = "폰으로 전송 중"
                val replier = Thread({ answerPings(input, out) }, "kcoach-probe-pong").apply { start() }
                try {
                    while (!stopping && replier.isAlive) {
                        val batch = synchronized(buffer) { buffer.take(MAX_PER_FRAME) }
                        if (batch.isNotEmpty()) {
                            val frame = ProbeWire.Samples(seq, SystemClock.elapsedRealtimeNanos(), powerManager.isInteractive, batch)
                            synchronized(writeLock) {
                                ProbeWire.write(out, frame)
                                out.flush()
                            }
                            seq++
                            // Only forget samples once the write went through.
                            synchronized(buffer) {
                                repeat(batch.size) { buffer.pollFirst() }
                                ProbeStatus.bufferedSamples = buffer.size
                            }
                            ProbeStatus.sentSamples += batch.size
                        }
                        Thread.sleep(SEND_INTERVAL_MS)
                    }
                } finally {
                    replier.interrupt()
                }
            } catch (_: InterruptedException) {
                break
            } catch (error: Throwable) {
                ProbeStatus.message = "재연결 대기: ${error.message ?: error.javaClass.simpleName}"
            } finally {
                ProbeStatus.connected = false
                channel?.let { runCatching { channelClient.close(it) } }
            }
            if (!stopping) runCatching { Thread.sleep(RECONNECT_DELAY_MS) }
        }
    }

    private fun answerPings(input: DataInputStream, out: DataOutputStream) {
        runCatching {
            while (!Thread.currentThread().isInterrupted) {
                val frame = ProbeWire.read(input)
                if (frame is ProbeWire.Ping) {
                    synchronized(writeLock) {
                        ProbeWire.write(out, ProbeWire.Pong(frame.phoneNanos, SystemClock.elapsedRealtimeNanos()))
                        out.flush()
                    }
                }
            }
        }
    }

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "KCoach 실측", NotificationManager.IMPORTANCE_LOW))
        val openProbe = PendingIntent.getActivity(
            this,
            0,
            Intent(this, ProbeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("KCoach 실측")
            .setContentText("센서를 폰으로 보내는 중")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setContentIntent(openProbe)
        // Wear OS treats a long-running workout as an Ongoing Activity (Now Bar
        // on Galaxy watches); without it the system sees a plain background app.
        OngoingActivity.Builder(applicationContext, NOTIFICATION_ID, builder)
            .setStaticIcon(android.R.drawable.ic_menu_compass)
            .setTouchIntent(openProbe)
            .setStatus(Status.Builder().addTemplate("KCoach 실측 중").build())
            .build()
            .apply(applicationContext)
        val notification: Notification = builder.build()
        runCatching {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
        }.onFailure {
            // Emulators without health permissions fall back to dataSync.
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        }
    }

    companion object {
        private const val CHANNEL_ID = "kcoach_probe"
        private const val NOTIFICATION_ID = 4210
        private const val SAMPLING_PERIOD_US = 10_000 // request 100 Hz
        private const val SEND_INTERVAL_MS = 50L
        private const val RECONNECT_DELAY_MS = 2_000L
        private const val MAX_PER_FRAME = 400
        private const val MAX_BUFFERED = 100 * 2 * 120 // two sensors, two minutes at 100 Hz
    }
}
