package com.ara.timer

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.PowerManager
import android.provider.MediaStore
import androidx.core.app.NotificationManagerCompat

/** Phone settings that would stop the rings from being heard or seen. */
data class DeviceStatus(
    val alarmVolumeMuted: Boolean = false,
    val notificationsOff: Boolean = false,
    val exactAlarmsDenied: Boolean = false,
    val batteryRestricted: Boolean = false,
) {
    companion object {
        fun read(context: Context): DeviceStatus {
            val audio = context.getSystemService(AudioManager::class.java)
            val power = context.getSystemService(PowerManager::class.java)
            val notifications = context.getSystemService(NotificationManager::class.java)
            val channelBlocked = notifications?.notificationChannels?.any {
                it.importance == NotificationManager.IMPORTANCE_NONE
            } == true
            return DeviceStatus(
                alarmVolumeMuted = audio != null && audio.getStreamVolume(AudioManager.STREAM_ALARM) == 0,
                notificationsOff = !NotificationManagerCompat.from(context).areNotificationsEnabled() ||
                    channelBlocked,
                exactAlarmsDenied = !Alarms.canScheduleExact(context),
                batteryRestricted = power != null && !power.isIgnoringBatteryOptimizations(context.packageName),
            )
        }
    }
}

/** What the "Sound" row shows. */
sealed interface RingtoneLabel {
    data class Named(val title: String) : RingtoneLabel
    data object Unnamed : RingtoneLabel
    data object Silent : RingtoneLabel

    companion object {
        /** Does disk/provider I/O: call off the main thread. */
        fun read(context: Context): RingtoneLabel {
            val uri = try {
                RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE)
            } catch (e: Exception) {
                return Unnamed
            } ?: return Silent
            return titleOf(context, uri)?.let { Named(it) } ?: Unnamed
        }

        private fun titleOf(context: Context, uri: Uri): String? {
            // Ringtone URIs usually carry their title, e.g. ...?title=Over the Horizon
            try {
                uri.getQueryParameter("title")?.takeIf { it.isNotBlank() }?.let { return it }
            } catch (e: Exception) {
                // Opaque URI.
            }
            try {
                context.contentResolver.query(
                    uri, arrayOf(MediaStore.MediaColumns.TITLE), null, null, null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        cursor.getString(0)?.takeIf { it.isNotBlank() }?.let { return it }
                    }
                }
            } catch (e: Exception) {
                // No permission to read this file's details.
            }
            try {
                val ringtone = RingtoneManager.getRingtone(context, uri) ?: return null
                val title = ringtone.getTitle(context)
                ringtone.stop() // releases the player some versions create eagerly
                if (!title.isNullOrBlank()) return title
            } catch (e: Exception) {
                // Ignore.
            }
            return null
        }
    }
}
