package com.ara.timer

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.PowerManager
import android.provider.MediaStore
import androidx.core.app.NotificationManagerCompat

/** Whether the ring's sound can be heard, given the phone's sound mode and ringtone volume. */
enum class RingSound { Audible, PhoneOnVibrate, PhoneOnMute, RingtoneVolumeOff }

/** Phone settings that would stop the rings from being heard or seen. */
data class DeviceStatus(
    val ringSound: RingSound = RingSound.Audible,
    /** Do Not Disturb is on: like a phone call's ringtone, the rings may be silenced. */
    val doNotDisturb: Boolean = false,
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
            val filter = notifications?.currentInterruptionFilter ?: NotificationManager.INTERRUPTION_FILTER_UNKNOWN
            return DeviceStatus(
                ringSound = ringSound(audio),
                doNotDisturb = filter != NotificationManager.INTERRUPTION_FILTER_ALL &&
                    filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN,
                notificationsOff = !NotificationManagerCompat.from(context).areNotificationsEnabled() ||
                    channelBlocked,
                exactAlarmsDenied = !Alarms.canScheduleExact(context),
                batteryRestricted = power != null && !power.isIgnoringBatteryOptimizations(context.packageName),
            )
        }

        private fun ringSound(audio: AudioManager?): RingSound = when {
            audio == null -> RingSound.Audible
            audio.ringerMode == AudioManager.RINGER_MODE_VIBRATE -> RingSound.PhoneOnVibrate
            audio.ringerMode == AudioManager.RINGER_MODE_SILENT -> RingSound.PhoneOnMute
            audio.getStreamVolume(AudioManager.STREAM_RING) == 0 -> RingSound.RingtoneVolumeOff
            else -> RingSound.Audible
        }
    }
}

object SoundSettings {
    /**
     * Switches the phone to Sound mode and makes sure the ringtone volume isn't zero,
     * then shows the volume panel so the user can set the level. Returns false when
     * Android doesn't let apps do it (leaving mute can count as changing Do Not
     * Disturb); the caller then opens the sound settings instead.
     */
    fun turnSoundOn(context: Context): Boolean {
        val audio = context.getSystemService(AudioManager::class.java) ?: return false
        return try {
            if (audio.ringerMode != AudioManager.RINGER_MODE_NORMAL) {
                audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
            }
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_RING)
            if (audio.getStreamVolume(AudioManager.STREAM_RING) == 0 && max > 0) {
                val half = (max + 1) / 2
                audio.setStreamVolume(AudioManager.STREAM_RING, half, AudioManager.FLAG_SHOW_UI)
            } else {
                audio.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI)
            }
            audio.ringerMode == AudioManager.RINGER_MODE_NORMAL
        } catch (e: SecurityException) {
            false
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
