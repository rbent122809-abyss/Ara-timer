package com.ara.timer

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import androidx.annotation.VisibleForTesting

/**
 * Plays the phone's default ringtone, looped, until [stop] is called.
 *
 * It uses the ALARM audio channel, so it is heard even when the phone is set to vibrate
 * or silent, at the phone's alarm volume. Other audio (music) is asked to duck. During a
 * phone or video call the sound is skipped and only the vibration runs.
 */
class RingPlayer(context: Context) {

    private val context = context.applicationContext
    private val audioManager: AudioManager? = this.context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private var player: MediaPlayer? = null
    private var tone: ToneGenerator? = null
    private var focusRequest: AudioFocusRequest? = null
    private var vibrator: Vibrator? = null
    private var modeListener: AudioManager.OnModeChangedListener? = null

    private val attributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    /** True while sound is coming out (ringtone or the fallback beeps). */
    val isSounding: Boolean
        get() = tone != null || try {
            player?.isPlaying == true
        } catch (e: IllegalStateException) {
            false
        }

    val isVibrating: Boolean get() = vibrator != null

    @VisibleForTesting
    internal val isLooping: Boolean get() = player?.isLooping == true

    fun start(vibrate: Boolean) {
        stop()
        try {
            if (isInCall()) {
                Log.i(TAG, "Call in progress: this ring vibrates only")
            } else {
                // Best effort only. Android 15+ may refuse focus to apps that are not on
                // screen; the ring must play regardless, so the result is not checked.
                requestFocus()
                if (!startRingtone()) startBeeps()
                watchForCalls()
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not start the ring sound", e)
        }
        if (vibrate) startVibration()
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        stopSound()
        stopVibration()
        stopWatchingForCalls()
        abandonFocus()
    }

    private fun stopSound() {
        player?.let { mp ->
            try {
                mp.stop()
            } catch (e: IllegalStateException) {
                // Not started or already in an error state.
            }
            mp.release()
        }
        player = null
        tone?.let { generator ->
            try {
                generator.stopTone()
            } catch (e: RuntimeException) {
                // Ignore.
            }
            generator.release()
        }
        tone = null
    }

    /** Phone call, video/VoIP call, or an incoming call ringing. */
    private fun isInCall(): Boolean {
        val mode = audioManager?.mode ?: return false
        return mode == AudioManager.MODE_IN_CALL ||
            mode == AudioManager.MODE_IN_COMMUNICATION ||
            mode == AudioManager.MODE_RINGTONE ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && mode == AudioManager.MODE_CALL_SCREENING)
    }

    private fun startRingtone(): Boolean {
        for (uri in candidateUris()) {
            val mp = MediaPlayer()
            try {
                mp.setAudioAttributes(attributes)
                mp.setDataSource(context, uri)
                mp.isLooping = true // short ringtones repeat to fill the 12 seconds
                mp.setOnErrorListener { failed, what, extra ->
                    Log.w(TAG, "Ringtone playback error $what/$extra")
                    if (player === failed) {
                        stopSound()
                        startBeeps()
                    }
                    true
                }
                mp.prepare()
                mp.start()
                player = mp
                return true
            } catch (e: Exception) {
                Log.w(TAG, "Cannot play $uri", e)
                mp.release()
            }
        }
        return false
    }

    /**
     * The phone's ringtone first. Settings.System.DEFAULT_RINGTONE_URI resolves to
     * whatever is set in Settings > Sounds (on a Galaxy A56 out of the box that is
     * Samsung's default ringtone). Falls back to the alarm and notification sounds if
     * the ringtone is set to silent or can't be read.
     */
    private fun candidateUris(): List<Uri> {
        val uris = mutableListOf<Uri>()
        uris += Settings.System.DEFAULT_RINGTONE_URI
        actualDefault(RingtoneManager.TYPE_RINGTONE)?.let { uris += it }
        uris += Settings.System.DEFAULT_ALARM_ALERT_URI
        actualDefault(RingtoneManager.TYPE_ALARM)?.let { uris += it }
        uris += Settings.System.DEFAULT_NOTIFICATION_URI
        return uris.distinct()
    }

    private fun actualDefault(type: Int): Uri? = try {
        RingtoneManager.getActualDefaultRingtoneUri(context, type)
    } catch (e: Exception) {
        null
    }

    /** Last resort if no ringtone file can be played: a repeating alarm beep. */
    private fun startBeeps() {
        val generator = try {
            ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME)
        } catch (e: RuntimeException) {
            Log.w(TAG, "No tone generator available", e)
            return
        }
        tone = generator
        val beep = object : Runnable {
            override fun run() {
                val current = tone ?: return
                current.startTone(ToneGenerator.TONE_PROP_BEEP2, BEEP_MS)
                handler.postDelayed(this, BEEP_PERIOD_MS)
            }
        }
        handler.post(beep)
    }

    private fun requestFocus() {
        val manager = audioManager ?: return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener({ change ->
                // Something more important took over the audio (e.g. a call): go quiet,
                // keep vibrating.
                if (change == AudioManager.AUDIOFOCUS_LOSS ||
                    change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                ) {
                    silence()
                }
            }, handler)
            .build()
        focusRequest = request
        manager.requestAudioFocus(request)
    }

    private fun abandonFocus() {
        val request = focusRequest ?: return
        focusRequest = null
        try {
            audioManager?.abandonAudioFocusRequest(request)
        } catch (e: RuntimeException) {
            // Ignore.
        }
    }

    /** Stops the sound when a call starts in the middle of a ring (Android 12+). */
    private fun watchForCalls() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val manager = audioManager ?: return
        val listener = AudioManager.OnModeChangedListener { if (isInCall()) silence() }
        try {
            manager.addOnModeChangedListener(context.mainExecutor, listener)
            modeListener = listener
        } catch (e: RuntimeException) {
            Log.w(TAG, "Cannot watch for calls", e)
        }
    }

    private fun stopWatchingForCalls() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val listener = modeListener ?: return
        modeListener = null
        try {
            audioManager?.removeOnModeChangedListener(listener)
        } catch (e: RuntimeException) {
            // Ignore.
        }
    }

    private fun silence() {
        handler.removeCallbacksAndMessages(null)
        stopSound()
    }

    private fun startVibration() {
        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
        if (device == null || !device.hasVibrator()) return
        val pattern = VibrationEffect.createWaveform(longArrayOf(0L, 800L, 600L), 0)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                device.vibrate(pattern, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
            } else {
                @Suppress("DEPRECATION")
                device.vibrate(pattern, attributes)
            }
            vibrator = device
        } catch (e: RuntimeException) {
            Log.w(TAG, "Vibration failed", e)
        }
    }

    private fun stopVibration() {
        val device = vibrator ?: return
        vibrator = null
        try {
            device.cancel()
        } catch (e: RuntimeException) {
            // Ignore.
        }
    }

    private companion object {
        const val TAG = "AraRingPlayer"
        const val BEEP_MS = 400
        const val BEEP_PERIOD_MS = 900L
    }
}
