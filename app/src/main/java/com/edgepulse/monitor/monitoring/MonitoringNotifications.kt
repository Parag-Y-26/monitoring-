package com.edgepulse.monitor.monitoring

import android.app.*
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.edgepulse.monitor.MainActivity
import com.edgepulse.monitor.R

object MonitoringNotifications {
    const val RUNNING_ID = 2101
    private const val RUNNING = "edgepulse.monitoring"
    private const val ALERT = "edgepulse.monitoring.alerts"
    fun channels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(RUNNING, "Active monitoring", NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel(ALERT, "Monitoring interruptions", NotificationManager.IMPORTANCE_HIGH).apply { enableVibration(true) })
    }
    fun ongoing(context: Context): Notification {
        channels(context)
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(context, 1, Intent(context, MonitoringForegroundService::class.java).setAction(MonitoringForegroundService.STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(context, RUNNING).setSmallIcon(R.drawable.ic_edgepulse)
            .setContentTitle(context.getString(R.string.monitoring_notification_title)).setContentText("Offline object detection · local motion watch")
            .setContentIntent(open).setOngoing(true).addAction(0, "Stop monitoring", stop).build()
    }
    fun alert(context: Context, text: String) {
        channels(context)
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val value = NotificationCompat.Builder(context, ALERT).setSmallIcon(R.drawable.ic_edgepulse)
            .setContentTitle(context.getString(R.string.attention_notification_title)).setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH).setContentIntent(open).setAutoCancel(true).build()
        context.getSystemService(NotificationManager::class.java).notify(2102, value)
    }
}
