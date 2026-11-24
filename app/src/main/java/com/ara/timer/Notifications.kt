package com.ara.timer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.text.format.DateFormat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.Date

object Notifications {
    const val ID = 1
    private const val CHANNEL_ID = "timer"
    private const val REQUEST_OPEN = 10
    private const val REQUEST_STOP = 11

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.channel_description)
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    fun build(context: Context, session: Session, ringing: Boolean): Notification {
        val openApp = PendingIntent.getActivity(
            context,
            REQUEST_OPEN,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getBroadcast(
            context,
            REQUEST_STOP,
            Intent(context, TimerReceiver::class.java).setAction(TimerReceiver.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_timer)
            .setColor(ContextCompat.getColor(context, R.color.green))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp)
            .addAction(R.drawable.ic_stop, context.getString(R.string.stop), stop)

        if (ringing) {
            builder
                .setContentTitle(context.getString(R.string.notif_title_ringing))
                .setContentText(context.getString(R.string.notif_text_ringing, session.ringCount))
                .setShowWhen(false)
                .setUsesChronometer(false)
        } else {
            val now = SystemClock.elapsedRealtime()
            val msLeft = (session.nextDueTime - now).coerceAtLeast(0L)
            val nextAt = DateFormat.getTimeFormat(context).format(Date(System.currentTimeMillis() + msLeft))
            val ringsSoFar = context.resources.getQuantityString(
                R.plurals.rings_so_far, session.ringCount, session.ringCount,
            )
            builder
                .setContentTitle(context.getString(R.string.notif_title_running))
                .setContentText(context.getString(R.string.notif_text_running, nextAt, ringsSoFar))
                // Live countdown to the next ring, drawn by the system.
                .setWhen(System.currentTimeMillis() + msLeft)
                .setShowWhen(true)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
        }
        return builder.build()
    }
}
