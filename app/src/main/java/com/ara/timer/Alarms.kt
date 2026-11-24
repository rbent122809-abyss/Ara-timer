package com.ara.timer

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.ApplicationExitInfo
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/** Exact wake-up alarms that fire even when the phone is asleep in Doze. */
object Alarms {
    private const val TAG = "AraAlarms"
    private const val REQUEST_ALARM = 1

    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return false
        return alarmManager.canScheduleExactAlarms()
    }

    /** Schedules (or replaces) the single pending ring alarm at [triggerAtElapsed]. */
    fun schedule(context: Context, triggerAtElapsed: Long) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val operation = pendingIntent(context)
        try {
            if (canScheduleExact(context)) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtElapsed, operation,
                )
                return
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Exact alarm not permitted, falling back to inexact", e)
        }
        // Inexact fallback. The service also holds a wake lock in this case so its own
        // timer stays accurate.
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtElapsed, operation,
        )
    }

    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        alarmManager.cancel(pendingIntent(context))
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, TimerReceiver::class.java).setAction(TimerReceiver.ACTION_ALARM)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_ALARM,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

object Device {
    /** Changes on every reboot; -1 if the platform doesn't report it. */
    fun bootCount(context: Context): Int = try {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
    } catch (e: Exception) {
        -1
    }

    /**
     * True if the user stopped the app after [sinceWallMillis]: with "Force stop" in
     * Settings, or the Stop button in the "Active apps" panel. That panel leaves the
     * app's alarms in place, so without this check the timer would start ringing
     * again on its own at the next alarm.
     */
    fun userStoppedAppSince(context: Context, sinceWallMillis: Long): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return try {
            val activityManager = context.getSystemService(ActivityManager::class.java) ?: return false
            val lastExit = activityManager
                .getHistoricalProcessExitReasons(context.packageName, 0, 1)
                .firstOrNull() ?: return false
            val stoppedByUser = lastExit.reason == ApplicationExitInfo.REASON_USER_REQUESTED ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM &&
                    lastExit.reason == ApplicationExitInfo.REASON_USER_STOPPED)
            stoppedByUser && lastExit.timestamp >= sinceWallMillis
        } catch (e: RuntimeException) {
            false
        }
    }
}

fun PowerManager.WakeLock.releaseSafely() {
    try {
        if (isHeld) release()
    } catch (e: RuntimeException) {
        // Already released by its timeout.
    }
}

/**
 * Bridges the gap between an alarm broadcast (whose wake lock ends when onReceive
 * returns) and the service starting up when the app's process had been killed.
 */
object BridgeWakeLock {
    private const val TIMEOUT_MS = 30_000L
    private var wakeLock: PowerManager.WakeLock? = null

    fun acquire(context: Context) {
        val lock = wakeLock ?: run {
            val powerManager = context.getSystemService(PowerManager::class.java) ?: return
            powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AraTimer:bridge").also {
                it.setReferenceCounted(false)
                wakeLock = it
            }
        }
        lock.acquire(TIMEOUT_MS)
    }

    fun release() {
        wakeLock?.releaseSafely()
    }
}
