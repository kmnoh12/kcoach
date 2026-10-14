package dev.kyungmin.kcoach.wear

import android.content.Context
import android.provider.Settings
import java.util.UUID

data class WearClockEpoch(val id: String, val verified: Boolean, val deviceId: String = "") {
    companion object {
        private val processEpoch = UUID.randomUUID().toString()
        @Synchronized fun current(context: Context): WearClockEpoch {
            val prefs = context.getSharedPreferences("capture-clock", Context.MODE_PRIVATE)
            val installation = prefs.getString("installation", null) ?: UUID.randomUUID().toString().also {
                check(prefs.edit().putString("installation", it).commit())
            }
            val boot = runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT) }.getOrNull()
                ?.takeIf { it >= 0 }
            return WearClockEpoch("watch-$installation-${boot ?: processEpoch}", boot != null, "watch-$installation")
        }
    }
}
