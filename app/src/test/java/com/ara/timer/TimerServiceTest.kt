package com.ara.timer

import android.Manifest
import android.app.ActivityManager
import android.app.AlarmManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.app.Notification
import android.content.Context
import android.media.AudioManager
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import com.ara.timer.ui.TimerUiState
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.shadows.ShadowActivityManager
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.shadows.ShadowToneGenerator
import org.robolectric.shadows.util.DataSource

/** Drives the real service, receiver and controller through whole sessions. */
@RunWith(RobolectricTestRunner::class)
class TimerServiceTest {

    private val app: Application = RuntimeEnvironment.getApplication()
    private val alarmManager: AlarmManager = app.getSystemService(AlarmManager::class.java)
    private val audioManager: AudioManager = app.getSystemService(AudioManager::class.java)
    private val controllers = mutableListOf<ServiceController<TimerService>>()

    private val five = TimerConfig.INTERVAL_MS
    private val ring = TimerConfig.RING_DURATION_MS

    @Before
    fun setUp() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        audioManager.mode = AudioManager.MODE_NORMAL
        audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
        audioManager.setStreamVolume(AudioManager.STREAM_RING, audioManager.getStreamMaxVolume(AudioManager.STREAM_RING), 0)
        // The phone's ringtone is a 3 second clip, so looping matters.
        ShadowMediaPlayer.addMediaInfo(
            DataSource.toDataSource(app, Settings.System.DEFAULT_RINGTONE_URI),
            ShadowMediaPlayer.MediaInfo(3_000, 0),
        )
    }

    @After
    fun tearDown() {
        TimerController.stop(app)
        controllers.forEach { runCatching { it.destroy() } }
        audioManager.mode = AudioManager.MODE_NORMAL
        audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
    }

    // --- helpers -----------------------------------------------------------------------

    private val session: Session get() = TimerRepository.session.value
    private val now: Long get() = SystemClock.elapsedRealtime()
    private val ringing: Boolean get() = session.isRinging

    /** Runs the main thread forward, firing any timers that come due. */
    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun advanceTo(time: Long) = advance(time - now)

    /** Moves the clock forward without running anything, like a phone in deep sleep. */
    private fun sleep(ms: Long) = ShadowSystemClock.advanceBy(Duration.ofMillis(ms))

    /** When ring n (1, 2, 3...) of a session started at [start] begins. */
    private fun ringTime(start: Long, n: Int) = start + n * five + (n - 1) * ring

    /** What the screen shows right now. */
    private fun screen() = TimerUiState.from(
        session = session,
        now = now,
        wallClockOffset = 0L,
        formatClock = { "" },
        ringtoneText = "",
        vibrate = true,
        previewing = false,
        warnings = emptyList(),
    )

    private fun startTimer(): TimerService {
        assertTrue(TimerController.start(app))
        return launchStartedService()
    }

    private fun launchStartedService(): TimerService {
        val intent = shadowOf(app).nextStartedService
        assertNotNull("TimerService should have been started", intent)
        assertEquals(TimerService::class.java.name, intent.component?.className)
        val controller = Robolectric.buildService(TimerService::class.java, intent).create().startCommand(0, 1)
        controllers += controller
        return controller.get()
    }

    /** Simulates Android killing the app's process: the service dies, the saved state stays. */
    private fun killProcess() {
        controllers.removeAt(controllers.lastIndex).destroy()
        assertNull(TimerService.instance)
        TimerRepository.init(app) // a new process reads the state back from disk
    }

    private fun nextAlarm(): ShadowAlarmManager.ScheduledAlarm? = shadowOf(alarmManager).peekNextScheduledAlarm()

    // --- the basic cycle ---------------------------------------------------------------

    @Test
    fun start_goesForeground_andArmsAnExactWakeUpForTheFirstRing() {
        val service = startTimer()

        assertTrue(session.running)
        assertEquals(Notifications.ID, shadowOf(service).lastForegroundNotificationId)
        val alarm = nextAlarm()!!
        assertEquals(AlarmManager.ELAPSED_REALTIME_WAKEUP, alarm.type)
        assertEquals(session.cycleStart + five, alarm.triggerAtMs)
        assertTrue(alarm.isAllowWhileIdle)
        assertFalse(ringing)
        assertFalse(service.holdsFallbackWakeLock)
        assertEquals("05:00", screen().countdownText)
    }

    @Test
    fun ringsAtFiveMinutes_timerPausesFor12Seconds_thenResumes() {
        val service = startTimer()
        val start = session.cycleStart

        advance(five - 1_000)
        assertFalse("must not ring before 5:00", ringing)
        assertEquals("00:01", screen().countdownText)
        assertEquals("4:59", screen().elapsedText)

        advance(1_000)
        assertTrue("rings at 5:00", ringing)
        assertEquals(1, session.rings)
        assertTrue(service.ringPlayer.isSounding)
        assertTrue("ringtone loops to fill 12 s", service.ringPlayer.isLooping)
        assertTrue(service.ringPlayer.isVibrating)
        assertTrue("CPU kept awake during the ring", service.holdsRingWakeLock)
        assertEquals("ring end is armed as a wake-up too", start + five + ring, nextAlarm()!!.triggerAtMs)

        // Paused for the whole ring.
        for (step in listOf(0L, 6_000L, 5_999L)) {
            advance(step)
            assertTrue(ringing)
            assertTrue(screen().ringing)
            assertEquals("elapsed time paused", "5:00", screen().elapsedText)
            assertEquals(1f, screen().cycleFraction)
        }
        assertEquals(11_999L, now - (start + five))
        assertTrue("still sounding just before 12 s", service.ringPlayer.isSounding)
        assertEquals(1, screen().ringSecondsLeft)

        advance(1)
        assertFalse("ring stops after exactly 12 s", ringing)
        assertFalse(service.ringPlayer.isSounding)
        assertFalse(service.ringPlayer.isVibrating)
        assertFalse(service.holdsRingWakeLock)
        assertEquals("countdown restarts at 5:00", "05:00", screen().countdownText)
        assertEquals("elapsed resumes from 5:00", "5:00", screen().elapsedText)
        assertEquals(start + five + ring + five, nextAlarm()!!.triggerAtMs)

        advance(1_000)
        assertEquals("04:59", screen().countdownText)
        assertEquals("5:01", screen().elapsedText)
    }

    @Test
    fun keepsRinging_afterEveryFiveMinutesOfTimer_untilStopped() {
        val service = startTimer()
        val start = session.cycleStart

        for (n in 1..12) { // one hour of timer time
            advanceTo(ringTime(start, n) - 1)
            assertFalse("ring $n not early", ringing)
            advance(1)
            assertTrue("ring $n on time", ringing)
            assertEquals(n, session.rings)
            assertEquals(if (n < 12) "${n * 5}:00" else "1:00:00", screen().elapsedText)
            advance(ring)
            assertFalse("ring $n lasts 12 s", ringing)
            assertEquals(ringTime(start, n + 1), nextAlarm()!!.triggerAtMs)
        }
        assertEquals("1:00:00", screen().elapsedText)

        TimerController.stop(app)
        assertFalse(session.running)
        assertNull("alarm cancelled", nextAlarm())
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertTrue(shadowOf(service).isForegroundStopped)

        advance(3 * five)
        assertFalse("no rings after Stop", ringing)
        assertFalse(service.ringPlayer.isSounding)
    }

    @Test
    fun stopDuringRing_silencesImmediately() {
        val service = startTimer()
        advance(five + 4_000)
        assertTrue(ringing)

        TimerController.stop(app)

        assertFalse(session.running)
        assertFalse(service.ringPlayer.isSounding)
        assertFalse(service.ringPlayer.isVibrating)
        assertFalse(service.holdsRingWakeLock)
        assertNull(nextAlarm())
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun notification_hasStopButton_andLiveCountdowns() {
        val service = startTimer()
        val counting = shadowOf(service).lastForegroundNotification
        assertEquals("Stop", counting.actions.single().title.toString())
        assertTrue(counting.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertTrue(counting.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN))
        assertTrue(counting.flags and Notification.FLAG_ONGOING_EVENT != 0)

        advance(five)
        val manager = app.getSystemService(android.app.NotificationManager::class.java)
        val ringingNotification = shadowOf(manager).getNotification(Notifications.ID)
        assertEquals("Ringing · timer paused", ringingNotification.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("Stop", ringingNotification.actions.single().title.toString())
    }

    // --- sleep, alarms and lateness ----------------------------------------------------

    @Test
    fun exactAlarmWakesASleepingPhone_andEachRingPlaysOnlyOnce() {
        val service = startTimer()
        val start = session.cycleStart

        sleep(five) // CPU asleep: the in-app timer did not run
        assertFalse(ringing)
        TimerController.onAlarm(app) // the exact alarm fires
        assertTrue(ringing)
        assertTrue(service.ringPlayer.isSounding)
        assertTrue(service.holdsRingWakeLock)

        // A duplicate delivery during the ring, or the in-app timer catching up, does nothing.
        TimerController.onAlarm(app)
        advance(0)
        assertEquals(1, session.rings)

        advance(ring)
        assertFalse(ringing)
        assertEquals(start + five + ring + five, nextAlarm()!!.triggerAtMs)

        // A stray late duplicate after the ring has finished must not ring again either.
        advance(20_000)
        TimerController.onAlarm(app)
        assertFalse(ringing)
        assertEquals(1, session.rings)
    }

    @Test
    fun ringEndAlarm_stopsTheRing_evenIfTheInAppTimerIsLate() {
        val service = startTimer()
        advance(five)
        assertTrue(ringing)

        sleep(ring) // the in-app timer did not get to run
        TimerController.onAlarm(app)

        assertFalse(ringing)
        assertFalse(service.ringPlayer.isSounding)
        assertEquals(session.cycleStart + five, nextAlarm()!!.triggerAtMs)
    }

    @Test
    fun lateAlarm_ringsWhenItArrives_andTheNextCountdownStartsAfterThatRing() {
        startTimer()
        val start = session.cycleStart

        sleep(five + 90_000) // the wake-up came 1.5 minutes late
        assertEquals("waiting at 00:00", "00:00", screen().countdownText)
        assertEquals("elapsed stopped at 5:00", "5:00", screen().elapsedText)
        TimerController.onAlarm(app)
        assertTrue(ringing)
        assertEquals(start + five + 90_000, session.ringStart)

        advance(ring)
        assertFalse(ringing)
        assertEquals("05:00", screen().countdownText)
        assertEquals(start + five + 90_000 + ring + five, nextAlarm()!!.triggerAtMs)
    }

    // --- the app's process being killed --------------------------------------------------

    @Test
    fun killedProcess_isRevivedByTheAlarm_andRings() {
        startTimer()
        val start = session.cycleStart
        killProcess()
        assertTrue(session.running)
        assertEquals(start, session.cycleStart)

        sleep(five)
        TimerController.onAlarm(app)
        val revived = launchStartedService()

        assertTrue(ringing)
        assertEquals(1, session.rings)
        assertTrue(revived.ringPlayer.isSounding)
        assertEquals(start + five + ring, nextAlarm()!!.triggerAtMs)
    }

    @Test
    fun killedMidRing_appReopenedDuringTheRing_soundResumesUntilTheOriginalEnd() {
        startTimer()
        advance(five + 5_000)
        val ringStart = session.ringStart
        killProcess()
        assertTrue(session.isRinging)

        TimerController.ensureService(app) // the user opens the app
        val revived = launchStartedService()

        assertTrue(revived.ringPlayer.isSounding)
        assertEquals("same ring, not a new one", ringStart, session.ringStart)
        assertEquals(1, session.rings)
        assertEquals("paused", "5:00", screen().elapsedText)
        advanceTo(ringStart + ring - 1)
        assertTrue(revived.ringPlayer.isSounding)
        advance(1)
        assertFalse(revived.ringPlayer.isSounding)
        assertEquals(ringStart + ring + five, nextAlarm()!!.triggerAtMs)
    }

    @Test
    fun killedMidRing_revivedAfterTheRingWouldHaveEnded_resumesCountingSilently() {
        startTimer()
        advance(five + 5_000)
        val ringStart = session.ringStart
        killProcess()

        sleep(ring) // the ring-end alarm wakes the app back up
        TimerController.onAlarm(app)
        val revived = launchStartedService()

        assertFalse(ringing)
        assertFalse(revived.ringPlayer.isSounding)
        assertEquals(ringStart + ring, session.cycleStart)
        assertEquals(ringStart + ring + five, nextAlarm()!!.triggerAtMs)
        assertEquals(1, session.rings)
    }

    @Test
    fun openingTheApp_restartsTheServiceForASavedSession() {
        startTimer()
        killProcess()

        TimerController.ensureService(app)

        val revived = launchStartedService()
        assertTrue(session.running)
        assertEquals(Notifications.ID, shadowOf(revived).lastForegroundNotificationId)
    }

    @Test
    fun sessionFromBeforeAReboot_isDiscarded() {
        Settings.Global.putInt(app.contentResolver, Settings.Global.BOOT_COUNT, 7)
        startTimer()
        killProcess()

        Settings.Global.putInt(app.contentResolver, Settings.Global.BOOT_COUNT, 8)
        TimerController.ensureService(app)

        assertFalse(session.running)
        assertNull(shadowOf(app).nextStartedService)
        assertNull(nextAlarm())
    }

    @Test
    fun userStoppedTheAppFromActiveApps_alarmDoesNotRestartTheTimer() {
        startTimer()
        advance(60_000)
        killProcess()
        shadowOf(app.getSystemService(ActivityManager::class.java)).addApplicationExitInfo(
            ShadowActivityManager.ApplicationExitInfoBuilder.newBuilder()
                .setProcessName(app.packageName)
                .setReason(ApplicationExitInfo.REASON_USER_REQUESTED)
                .setTimestamp(session.startWall + 60_000) // a minute after Start
                .build(),
        )

        sleep(five)
        TimerController.onAlarm(app)

        assertFalse("user stop counts as Stop", session.running)
        assertNull(shadowOf(app).nextStartedService)
        assertNull(nextAlarm())
    }

    @Test
    fun aForceStopFromBeforeThisSession_isIgnored() {
        shadowOf(app.getSystemService(ActivityManager::class.java)).addApplicationExitInfo(
            ShadowActivityManager.ApplicationExitInfoBuilder.newBuilder()
                .setProcessName(app.packageName)
                .setReason(ApplicationExitInfo.REASON_USER_REQUESTED)
                .setTimestamp(System.currentTimeMillis() - 3_600_000) // an hour before Start
                .build(),
        )
        startTimer()
        killProcess()

        sleep(five)
        TimerController.onAlarm(app)
        launchStartedService()

        assertTrue(session.running)
        assertTrue(ringing)
    }

    @Test
    fun serviceThatCannotGoForeground_stopsCleanly_andTheAlarmStaysArmed() {
        assertTrue(TimerController.start(app))
        val intent = shadowOf(app).nextStartedService
        val controller = Robolectric.buildService(TimerService::class.java, intent).create()
        controllers += controller
        shadowOf(controller.get()).setThrowInStartForeground(IllegalStateException("not allowed"))

        controller.startCommand(0, 1)

        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
        assertTrue("session kept so the alarm can retry", session.running)
        assertNotNull(nextAlarm())
    }

    @Test
    fun sessionSavedByVersion1_isDiscardedOnUpgrade() {
        val prefs = app.getSharedPreferences("ara_timer", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("running", true).putLong("start_elapsed", 5L).putInt("last_index", 2).commit()

        TimerRepository.init(app)

        assertFalse(session.running)
        assertFalse(prefs.contains("running"))
        assertFalse(prefs.contains("last_index"))
    }

    // --- misc ----------------------------------------------------------------------------

    @Test
    fun pressingStartAgain_keepsTheRunningSession() {
        startTimer()
        val first = session
        advance(60_000)

        assertTrue(TimerController.start(app))

        assertEquals(first.cycleStart, session.cycleStart)
    }

    @Test
    fun pressingStart_withALeftoverSessionFromBeforeAReboot_startsAFreshOne() {
        Settings.Global.putInt(app.contentResolver, Settings.Global.BOOT_COUNT, 7)
        startTimer()
        killProcess()
        Settings.Global.putInt(app.contentResolver, Settings.Global.BOOT_COUNT, 8)
        advance(60_000)

        val service = startTimer()

        assertTrue(session.running)
        assertEquals(8, session.bootCount)
        assertEquals(now, session.cycleStart)
        assertEquals(Notifications.ID, shadowOf(service).lastForegroundNotificationId)
        assertEquals(now + five, nextAlarm()!!.triggerAtMs)
    }

    @Test
    fun withoutExactAlarmPermission_holdsAWakeLockSoTheTimerStillWorks() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        val service = startTimer()

        assertTrue(service.holdsFallbackWakeLock)
        assertNotNull(nextAlarm())

        advance(five)
        assertTrue(ringing)
        advance(ring)
        assertFalse(ringing)
        assertTrue(service.holdsFallbackWakeLock)
    }

    // --- sound -----------------------------------------------------------------------------

    @Test
    fun ringPlaysOnTheRingtoneChannel_soThePhoneVolumeButtonsControlIt() {
        val service = startTimer()
        advance(five)

        val player = service.ringPlayer.mediaPlayer!!
        assertEquals(
            "ringtone channel (volume buttons, ringtone slider, vibrate/mute), not the alarm channel",
            android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE,
            shadowOf(player).audioAttributes.usage,
        )
        assertEquals(1f, shadowOf(player).leftVolume)
    }

    @Test
    fun inAppVolumeSlider_scalesTheRing() {
        TimerController.setRingVolume(0.5f)
        val service = startTimer()
        advance(five)

        val player = shadowOf(service.ringPlayer.mediaPlayer!!)
        assertEquals(0.25f, player.leftVolume, 0.0001f) // 50% on the slider sounds about half as loud
        assertEquals(0.25f, player.rightVolume, 0.0001f)
    }

    @Test
    fun movingTheSliderDuringARing_changesTheVolumeAtOnce() {
        val service = startTimer()
        advance(five + 3_000)
        val player = shadowOf(service.ringPlayer.mediaPlayer!!)
        assertEquals(1f, player.leftVolume)

        TimerController.setRingVolume(0.3f)

        assertEquals(0.09f, player.leftVolume, 0.0001f)
        assertTrue(service.ringPlayer.isSounding)
        advance(ring)
        advanceTo(session.ringDue)
        assertEquals("next ring keeps the new volume", 0.09f, shadowOf(service.ringPlayer.mediaPlayer!!).leftVolume, 0.0001f)
    }

    @Test
    fun volumeSetting_isRemembered_andKeptInRange() {
        TimerController.setRingVolume(0.4f)
        TimerRepository.init(app) // app restarted
        assertEquals(0.4f, TimerRepository.ringVolume.value)

        TimerController.setRingVolume(0f)
        assertEquals(TimerConfig.MIN_RING_VOLUME, TimerRepository.ringVolume.value)
        TimerController.setRingVolume(7f)
        assertEquals(1f, TimerRepository.ringVolume.value)
        TimerController.setRingVolume(Float.NaN)
        assertEquals(TimerConfig.DEFAULT_RING_VOLUME, TimerRepository.ringVolume.value)
    }

    @Test
    fun phoneOnVibrate_ringOnlyBuzzes_andDoesNotDuckMusic() {
        audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE
        val service = startTimer()

        advance(five)

        assertTrue("still the 12 s pause", ringing)
        assertFalse(service.ringPlayer.isSounding)
        assertTrue(service.ringPlayer.isVibrating)
        assertNull("no audio focus taken", shadowOf(audioManager).lastAudioFocusRequest)
        advance(ring)
        assertFalse(service.ringPlayer.isVibrating)
    }

    @Test
    fun ringtoneVolumeAtZero_ringOnlyBuzzes() {
        audioManager.setStreamVolume(AudioManager.STREAM_RING, 0, 0)
        val service = startTimer()

        advance(five)

        assertFalse(service.ringPlayer.isSounding)
        assertTrue(service.ringPlayer.isVibrating)
    }

    @Test
    fun audioFocusRefused_theRingtoneStillPlays() {
        // Android 15+ can refuse audio focus to apps that are not on screen.
        shadowOf(audioManager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        val service = startTimer()

        advance(five)

        assertTrue(ringing)
        assertTrue(service.ringPlayer.isSounding)
    }

    @Test
    fun duringAPhoneCall_theRingVibratesOnly() {
        val service = startTimer()
        audioManager.mode = AudioManager.MODE_IN_CALL

        advance(five)

        assertTrue(ringing)
        assertFalse(service.ringPlayer.isSounding)
        assertTrue(service.ringPlayer.isVibrating)
        advance(ring)
        assertFalse(service.ringPlayer.isVibrating)
    }

    @Test
    fun callStartingMidRing_silencesTheSound_butKeepsVibrating() {
        val service = startTimer()
        advance(five + 2_000)
        assertTrue(service.ringPlayer.isSounding)

        audioManager.mode = AudioManager.MODE_IN_CALL
        advance(0)

        assertFalse(service.ringPlayer.isSounding)
        assertTrue(service.ringPlayer.isVibrating)
        assertTrue("still the 12 s pause", ringing)
    }

    @Test
    fun vibrationCanBeTurnedOff() {
        TimerController.setVibrate(false)
        val service = startTimer()

        advance(five)

        assertTrue(service.ringPlayer.isSounding)
        assertFalse(service.ringPlayer.isVibrating)
    }

    @Test
    fun unreadableRingtone_fallsBackToAlarmBeeps() {
        ShadowMediaPlayer.resetStaticState() // no playable sound files at all
        val service = startTimer()

        advance(five)

        assertTrue(ringing)
        assertTrue(service.ringPlayer.isSounding)
        advance(2_000)
        assertTrue(ShadowToneGenerator.getPlayedTones().isNotEmpty())
        advance(ring)
        assertFalse(service.ringPlayer.isSounding)
    }
}
