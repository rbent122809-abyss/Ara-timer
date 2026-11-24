package com.ara.timer

import android.app.Application
import android.media.AudioManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** The checks behind the "rings will only buzz / be silent" cards and their Sound on button. */
@RunWith(RobolectricTestRunner::class)
class DeviceStatusTest {

    private val app: Application = RuntimeEnvironment.getApplication()
    private val audio: AudioManager = app.getSystemService(AudioManager::class.java)
    private val maxRing = audio.getStreamMaxVolume(AudioManager.STREAM_RING)

    @After
    fun tearDown() {
        audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
    }

    private fun ringSound() = DeviceStatus.read(app).ringSound

    @Test
    fun soundModeWithVolume_isAudible() {
        audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
        audio.setStreamVolume(AudioManager.STREAM_RING, maxRing, 0)
        assertEquals(RingSound.Audible, ringSound())
    }

    @Test
    fun vibrateMode_isReported() {
        audio.ringerMode = AudioManager.RINGER_MODE_VIBRATE
        assertEquals(RingSound.PhoneOnVibrate, ringSound())
    }

    @Test
    fun muteMode_isReported() {
        audio.ringerMode = AudioManager.RINGER_MODE_SILENT
        assertEquals(RingSound.PhoneOnMute, ringSound())
    }

    @Test
    fun ringtoneVolumeAtZero_isReported() {
        audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
        audio.setStreamVolume(AudioManager.STREAM_RING, 0, 0)
        assertEquals(RingSound.RingtoneVolumeOff, ringSound())
    }

    @Test
    fun doNotDisturb_isReported() {
        val notifications = app.getSystemService(android.app.NotificationManager::class.java)
        assertEquals(false, DeviceStatus.read(app).doNotDisturb)
        org.robolectric.Shadows.shadowOf(notifications).setNotificationPolicyAccessGranted(true)
        notifications.setInterruptionFilter(android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        assertEquals(true, DeviceStatus.read(app).doNotDisturb)
        notifications.setInterruptionFilter(android.app.NotificationManager.INTERRUPTION_FILTER_ALL)
        assertEquals(false, DeviceStatus.read(app).doNotDisturb)
    }

    @Test
    fun soundOn_leavesVibrateMode() {
        audio.ringerMode = AudioManager.RINGER_MODE_VIBRATE
        audio.setStreamVolume(AudioManager.STREAM_RING, 3, 0)

        assertTrue(SoundSettings.turnSoundOn(app))

        assertEquals(AudioManager.RINGER_MODE_NORMAL, audio.ringerMode)
        assertEquals("existing volume kept", 3, audio.getStreamVolume(AudioManager.STREAM_RING))
        assertEquals(RingSound.Audible, ringSound())
    }

    @Test
    fun soundOn_turnsAZeroRingtoneVolumeUpToHalf() {
        audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
        audio.setStreamVolume(AudioManager.STREAM_RING, 0, 0)

        assertTrue(SoundSettings.turnSoundOn(app))

        assertEquals((maxRing + 1) / 2, audio.getStreamVolume(AudioManager.STREAM_RING))
        assertEquals(RingSound.Audible, ringSound())
    }

    @Test
    fun volumeSliderCurve() {
        assertEquals(1f, RingPlayer.gainFor(1f))
        assertEquals(0.25f, RingPlayer.gainFor(0.5f), 0.0001f)
        assertEquals(0.01f, RingPlayer.gainFor(TimerConfig.MIN_RING_VOLUME), 0.0001f)
        assertEquals(0f, RingPlayer.gainFor(-1f))
        assertEquals(1f, RingPlayer.gainFor(3f))
    }
}
