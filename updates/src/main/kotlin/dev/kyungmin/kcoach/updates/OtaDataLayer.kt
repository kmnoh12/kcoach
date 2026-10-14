package dev.kyungmin.kcoach.updates

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.android.gms.wearable.*

interface OtaLink {
    fun command(command: OtaCommand)
    fun state(state: OtaDeviceState)
    fun recover(receive: (String, String, String) -> Unit)
}

class OtaDataLayer(private val context: Context) : OtaLink {
    override fun command(command: OtaCommand) = put(COMMAND_PATH, command.encode())
    override fun state(state: OtaDeviceState) = put(STATE_PATH, state.encode())
    private fun put(path: String, value: String) {
        runCatching {
            val request = PutDataMapRequest.create(path)
            request.dataMap.putString("payload", value)
            Wearable.getDataClient(context).putDataItem(request.asPutDataRequest().setUrgent())
                .addOnFailureListener { /* The source transaction stays in preferences for reconnect/recovery. */ }
        }
    }
    override fun recover(receive: (String, String, String) -> Unit) {
        runCatching {
            Wearable.getDataClient(context).dataItems.addOnSuccessListener { buffer ->
                try { buffer.forEach { item ->
                    val path = item.uri.path.orEmpty()
                    if (path in setOf(COMMAND_PATH, STATE_PATH)) {
                        val payload = DataMapItem.fromDataItem(item).dataMap.getString("payload").orEmpty()
                        receive(path, payload, item.uri.host.orEmpty())
                    }
                } } finally { buffer.release() }
            }.addOnFailureListener { /* Cached/source transactions remain retryable. */ }
        }
    }
    companion object {
        const val COMMAND_PATH = "/kcoach/update/phone-command"
        const val STATE_PATH = "/kcoach/update/watch-state"
        fun receive(context: Context, path: String, text: String, node: String): Boolean {
            if (path !in setOf(COMMAND_PATH, STATE_PATH)) return false
            runCatching { OtaCoordinator.get(context).receive(path, text, node) }
            return true
        }
        fun receive(context: Context, events: DataEventBuffer) {
            events.forEach { event ->
                if (event.type == DataEvent.TYPE_CHANGED && event.dataItem.uri.path in setOf(COMMAND_PATH, STATE_PATH)) {
                    val item = event.dataItem
                    receive(context, item.uri.path!!, DataMapItem.fromDataItem(item).dataMap.getString("payload").orEmpty(), item.uri.host.orEmpty())
                }
            }
        }
    }
}
