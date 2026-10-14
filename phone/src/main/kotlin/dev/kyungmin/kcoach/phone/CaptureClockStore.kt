package dev.kyungmin.kcoach.phone

import android.content.Context
import android.os.SystemClock
import dev.kyungmin.kcoach.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Original exchanges are durable; an estimate does not certify motion alignment. */
class CaptureClockStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("capture-clock", Context.MODE_PRIVATE)

    fun prepareProbe(captureId: String, window: String): Result<WireEnvelope> {
        val epoch = PhoneClockEpoch.current(context)
        return prepareProbe(captureId, window, SystemClock.elapsedRealtimeNanos(), epoch.id, epoch.verified)
    }

    fun prepareProbe(captureId: String, window: String, sentNs: Long, clockId: String, verified: Boolean): Result<WireEnvelope> = synchronized(lock) {
        runCatching {
            require(captureId.matches(Regex("[A-Za-z0-9._-]{1,160}")) && window in setOf("START", "MIDDLE", "END"))
            val request = ClockProbeProtocol.request(captureId, UUID.randomUUID().toString(), sentNs, clockId, verified)
            val pending = JSONObject(prefs.getString("pending", "{}")!!)
            // Pending probes contain no sensor data; expire old requests, never captured samples.
            pending.keys().asSequence().toList().forEach { key ->
                val old = WireCodec.decode(pending.getJSONObject(key).getString("request"))
                if (old.payload["phoneClockId"] != clockId || sentNs - old.payload.getValue("phoneSendNs").toLong() > 5_000_000_000L) pending.remove(key)
            }
            require(pending.length() < 64) { "Too many pending clock probes" }
            pending.put(request.messageId, JSONObject().put("request", WireCodec.encode(request)).put("window", window))
            check(prefs.edit().putString("pending", pending.toString()).commit())
            request
        }
    }

    fun acceptReply(reply: WireEnvelope, receiveNs: Long, currentClockId: String): Result<ClockProbeSample> = synchronized(lock) {
        runCatching {
            val pending = JSONObject(prefs.getString("pending", "{}")!!)
            val id = reply.payload.getValue("requestId")
            val original = pending.optJSONObject(id) ?: error("Unknown or consumed clock probe")
            val request = WireCodec.decode(original.getString("request"))
            val sample = ClockProbeProtocol.exchange(request, reply, receiveNs, currentClockId)
            require(receiveNs - sample.exchange.phoneSendNs <= 5_000_000_000L) { "Expired clock probe" }
            val key = samplesKey(request.sessionId, original.getString("window"))
            val values = JSONArray(prefs.getString(key, "[]")!!)
            require(values.length() < 64) { "Clock window sample limit reached" }
            values.put(JSONObject().put("request", WireCodec.encode(request)).put("reply", WireCodec.encode(reply)).put("receivedNs", receiveNs))
            pending.remove(id)
            check(prefs.edit().putString(key, values.toString()).putString("pending", pending.toString()).commit())
            sample
        }
    }

    fun samples(captureId: String, window: String): List<ClockProbeSample> = synchronized(lock) {
        val values = JSONArray(prefs.getString(samplesKey(captureId, window), "[]")!!)
        (0 until values.length()).map { index ->
            val item = values.getJSONObject(index)
            val request = WireCodec.decode(item.getString("request"))
            ClockProbeProtocol.exchange(request, WireCodec.decode(item.getString("reply")), item.getLong("receivedNs"),
                request.payload.getValue("phoneClockId"))
        }
    }

    fun alignment(captureId: String, window: String): Result<ClockAlignment> = runCatching {
        val samples = samples(captureId, window)
        require(samples.isNotEmpty() && samples.all { it.epochsVerified }) { "Clock epochs were not verified" }
        require(samples.map { it.phoneClockId }.distinct().size == 1 && samples.map { it.watchClockId }.distinct().size == 1) { "Clock epoch changed during capture" }
        ClockAlignment.estimate(samples.map { it.exchange }, samples.first().phoneClockId, samples.first().watchClockId)
    }

    fun pairedCaptureSupported(captureId: String): Boolean = synchronized(lock) {
        val values = JSONArray(prefs.getString(samplesKey(captureId, "START"), "[]")!!)
        val replies = (0 until values.length()).map { WireCodec.decode(values.getJSONObject(it).getString("reply")) }
        replies.isNotEmpty() && replies.map { it.payload["watchClockId"] }.distinct().size == 1 && replies.all {
            it.payload["pairedReferenceSupported"] == "true" && "2" in it.payload["captureFormats"].orEmpty().split(",")
        }
    }

    fun patternShadowSupported(captureId: String): Boolean = synchronized(lock) {
        val values = JSONArray(prefs.getString(samplesKey(captureId, "START"), "[]")!!)
        val replies = (0 until values.length()).map { WireCodec.decode(values.getJSONObject(it).getString("reply")) }
        pairedCaptureSupported(captureId) && replies.all {
            it.payload["patternShadowCaptureSupported"] == "true" && it.payload["patternProfileFormat"] == "1"
        }
    }

    fun patternActiveSupported(captureId: String): Boolean = synchronized(lock) {
        val values = JSONArray(prefs.getString(samplesKey(captureId, "START"), "[]")!!)
        patternShadowSupported(captureId) && (0 until values.length()).all {
            WireCodec.decode(values.getJSONObject(it).getString("reply")).payload["patternActiveBindingSupported"] == "true"
        }
    }

    fun startWristContextSupported(captureId: String): Boolean = synchronized(lock) {
        val values = JSONArray(prefs.getString(samplesKey(captureId, "START"), "[]")!!)
        pairedCaptureSupported(captureId) && (0 until values.length()).all {
            WireCodec.decode(values.getJSONObject(it).getString("reply")).payload["startWristContextFormat"] == "1"
        }
    }

    private fun samplesKey(captureId: String, window: String) = "samples:$captureId:$window"
    companion object { private val lock = Any() }
}
