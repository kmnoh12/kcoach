package dev.kyungmin.kcoach.updates

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.work.ForegroundInfo

internal object OtaNotifications {
    private const val CHANNEL = "kcoach-updates"
    private const val PROGRESS = 7301
    private const val INSTALL = 7302
    private fun builder(context: Context): Notification.Builder {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "KCoach 업데이트", NotificationManager.IMPORTANCE_DEFAULT))
        return Notification.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("KCoach 업데이트")
    }
    fun foreground(context: Context) = ForegroundInfo(PROGRESS,
        builder(context).setContentText("업데이트 다운로드 진행 중").setOngoing(true).build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    fun offerInstall(context: Context, state: OtaDeviceState) {
        val intent = Intent(context, OtaInstallActivity::class.java).putExtra("id", state.id)
        offer(context, intent, "${state.targetName} · 설치를 확인해 주세요")
    }
    fun offer(context: Context, intent: Intent, text: String) {
        val pending = PendingIntent.getActivity(context, INSTALL, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        runCatching { context.getSystemService(NotificationManager::class.java).notify(INSTALL,
            builder(context).setContentText(text).setContentIntent(pending).setAutoCancel(true).build()) }
    }
    fun clear(context: Context) = context.getSystemService(NotificationManager::class.java).cancel(INSTALL)
}
