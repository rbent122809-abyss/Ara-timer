package com.ara.timer

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.Notification
import android.media.AudioManager
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
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
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.shadows.ShadowToneGenerator
import org.robolectric.shadows.util.DataSource

@RunWith(RobolectricTestRunner::class)
class TimerServiceTest {

    private val app: Application = RuntimeEnvironment.getApplication()
    private val alarmManager: AlarmManager = app.getSystemService(AlarmManager::class.java)
    private val controllers = mutableListOf<ServiceController<TimerService>>()

    private val minutes5 = TimerConfig.INTERVAL_MS
    private val ring = TimerConfig.RING_DURATION_MS

    @Before
    fun setUp() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
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
    }

    // --- helpers -----------------------------------------------------------------------

    private val session: Session get() = TimerRepository.session.value
    private val ringing: Boolean get() = TimerRepository.ringingUntil.value > SystemClock.elapsedRealtime()

    /** Runs the main thread forward, firing any timers that come due. */
    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    /** Moves the clock forward without running anything, like a phone in deep sleep. */
    private fun sleep(ms: Long) = ShadowSystemClock.advanceBy(Duration.ofMillis(ms))

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

    private fun nextAlarm(): ShadowAlarmManager.ScheduledAlarm? = shadowOf(alarmManager).peekNextScheduledAlarm()

    // --- tests -------------------------------------------------------------------------

    @Test
    fun start_goesForeground_andSchedulesExactWakeUpAlarmFiveMinutesOut() {
        val service = startTimer()

        assertTrue(session.running)
        assertEquals(Notifications.ID, shadowOf(service).lastForegroundNotificationId)
        val alarm = nextAlarm()!!
        assertEquals(AlarmManager.ELAPSED_REALTIME_WAKEUP, alarm.type)
        assertEquals(session.startElapsed + minutes5, alarm.triggerAtMs)
        assertTrue(alarm.isAllowWhileIdle)
        assertFalse(ringing)
        assertFalse(service.holdsFallbackWakeLock)
    }

    @Test
    fun notification_hasStopButton_andLiveCountdown() {
        val service = startTimer()
        val notification = shadowOf(service).lastForegroundNotification

        assertEquals("Stop", notification.actions.single().title.toString())
        assertTrue(notification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertTrue(notification.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN))
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
    }

    @Test
    fun ringsAtFiveMinutes_forExactlyTwelveSeconds_thenStops() {
        val service = startTimer()
        val start = session.startElapsed

        advance(minutes5 - 1_000)
        assertFalse("must not ring before 5:00", ringing)
        assertEquals(0, session.ringCount)

        advance(1_000)
        assertTrue("rings at 5:00", ringing)
        assertEquals(1, session.ringCount)
        assertTrue(service.ringPlayer.isSounding)
        assertTrue("ringtone loops to fill 12 s", service.ringPlayer.isLooping)
        assertTrue(service.ringPlayer.isVibrating)
        assertTrue("CPU kept awake during the ring", service.holdsRingWakeLock)

        advance(ring - 1)
        assertTrue("still ringing just before 12 s", ringing)
        assertTrue(service.ringPlayer.isSounding)

        advance(1)
        assertFalse("ring stops after 12 s", ringing)
        assertFalse(service.ringPlayer.isSounding)
        assertFalse(service.ringPlayer.isVibrating)
        assertFalse(service.holdsRingWakeLock)

        assertEquals(start + 2 * minutes5, nextAlarm()!!.triggerAtMs)
    }

    @Test
    fun keepsRingingEveryFiveMinutes_untilStopped() {
        val service = startTimer()
        val start = session.startElapsed

        for (n in 1..12) { // one hour
            advance(start + n * minutes5 - SystemClock.elapsedRealtime())
            assertTrue("ring $n starts on time", ringing)
            assertEquals(n, session.ringCount)
            advance(ring)
            assertFalse("ring $n stops after 12 s", ringing)
            assertEquals(start + (n + 1) * minutes5, nextAlarm()!!.triggerAtMs)
        }

        TimerController.stop(app)
        assertFalse(session.running)
        assertNull("alarm cancelled", nextAlarm())
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertTrue(shadowOf(service).isForegroundStopped)

        advance(3 * minutes5)
        assertFalse("no rings after Stop", ringing)
        assertEquals(0, session.ringCount)
    }

    @Test
    fun stopDuringRing_silencesImmediately() {
        val service = startTimer()
        advance(minutes5)
        advance(4_000)
        assertTrue(ringing)

        TimerController.stop(app)

        assertFalse(ringing)
        assertFalse(service.ringPlayer.isSounding)
        assertFalse(service.ringPlayer.isVibrating)
        assertFalse(service.holdsRingWakeLock)
        assertNull(nextAlarm())
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun exactAlarmWakesSleepingPhone_andRingsOnlyOnce() {
        val service = startTimer()
        val start = session.startElapsed

        sleep(minutes5) // CPU asleep: in-app timer did not run
        assertFalse(ringing)
        TimerController.onAlarm(app) // the exact alarm fires
        assertTrue(ringing)
        assertTrue(service.ringPlayer.isSounding)
        assertTrue(service.holdsRingWakeLock)

        // The in-app timer and a duplicate delivery must not ring a second time.
        TimerController.onAlarm(app)
        advance(0)
        assertEquals(1, session.ringCount)

        advance(ring)
        assertFalse(ringing)
        assertEquals(start + 2 * minutes5, nextAlarm()!!.triggerAtMs)

        // A stray, late duplicate after the ring has finished must not ring again either.
        advance(20_000)
        TimerController.onAlarm(app)
        assertFalse(ringing)
        assertEquals(1, session.ringCount)
    }

    @Test
    fun slightlyLateAlarm_stillRings_butVeryLateOneIsSkipped() {
        startTimer()
        val start = session.startElapsed

        sleep(minutes5 + 90_000) // 1.5 minutes late
        TimerController.onAlarm(app)
        assertTrue(ringing)
        assertEquals(1, session.ringCount)
        advance(ring)

        sleep(start + 2 * minutes5 + 3 * 60_000 - SystemClock.elapsedRealtime()) // 3 minutes late
        TimerController.onAlarm(app)
        assertFalse("stale ring skipped", ringing)
        assertEquals(1, session.ringCount)
        assertEquals(2, session.lastIndex)
        assertEquals("schedule stays on the 5 minute grid", start + 3 * minutes5, nextAlarm()!!.triggerAtMs)

        advance(start + 3 * minutes5 - SystemClock.elapsedRealtime())
        assertTrue(ringing)
        assertEquals(2, session.ringCount)
    }

    @Test
    fun killedProcess_isRevivedByAlarm_andRings() {
        startTimer()
        val start = session.startElapsed

        // The process dies: the service is gone and only the saved state remains.
        controllers.removeAt(controllers.lastIndex).destroy()
        assertNull(TimerService.instance)
        TimerRepository.init(app)
        assertTrue(session.running)
        assertEquals(start, session.startElapsed)

        sleep(minutes5)
        TimerController.onAlarm(app)
        val revived = launchStartedService()

        assertTrue(ringing)
        assertEquals(1, session.ringCount)
        assertTrue(revived.ringPlayer.isSounding)
        assertEquals(start + 2 * minutes5, nextAlarm()!!.triggerAtMs)
    }

    @Test
    fun openingTheApp_restartsServiceForSavedSession() {
        startTimer()
        controllers.removeAt(controllers.lastIndex).destroy()

        TimerController.ensureService(app)

        val revived = launchStartedService()
        assertTrue(session.running)
        assertEquals(Notifications.ID, shadowOf(revived).lastForegroundNotificationId)
    }

    @Test
    fun sessionFromBeforeAReboot_isDiscarded() {
        Settings.Global.putInt(app.contentResolver, Settings.Global.BOOT_COUNT, 7)
        startTimer()
        controllers.removeAt(controllers.lastIndex).destroy()

        Settings.Global.putInt(app.contentResolver, Settings.Global.BOOT_COUNT, 8)
        TimerController.ensureService(app)

        assertFalse(session.running)
        assertNull(shadowOf(app).nextStartedService)
        assertNull(nextAlarm())
    }

    @Test
    fun startTwice_keepsTheOriginalSession() {
        startTimer()
        val first = session
        advance(60_000)

        assertTrue(TimerController.start(app))

        assertEquals(first.startElapsed, session.startElapsed)
    }

    @Test
    fun withoutExactAlarmPermission_holdsWakeLockSoTimerStillWorks() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        val service = startTimer()

        assertTrue(service.holdsFallbackWakeLock)
        assertNotNull(nextAlarm())

        advance(minutes5)
        assertTrue(ringing)
    }

    @Test
    fun duringPhoneCall_vibratesButStaysQuiet() {
        val service = startTimer()
        shadowOf(app.getSystemService(AudioManager::class.java))
            .setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)

        advance(minutes5)

        assertTrue(ringing)
        assertFalse(service.ringPlayer.isSounding)
        assertTrue(service.ringPlayer.isVibrating)
    }

    @Test
    fun vibrationCanBeTurnedOff() {
        TimerController.setVibrate(false)
        val service = startTimer()

        advance(minutes5)

        assertTrue(service.ringPlayer.isSounding)
        assertFalse(service.ringPlayer.isVibrating)
    }

    @Test
    fun unreadableRingtone_fallsBackToAlarmBeeps() {
        ShadowMediaPlayer.resetStaticState() // no playable sound files at all
        val service = startTimer()

        advance(minutes5)

        assertTrue(ringing)
        assertTrue(service.ringPlayer.isSounding)
        advance(2_000)
        assertTrue(ShadowToneGenerator.getPlayedTones().isNotEmpty())
        advance(ring)
        assertFalse(service.ringPlayer.isSounding)
    }

    @Test
    fun serviceThatCannotGoForeground_stopsCleanly_andAlarmStaysArmed() {
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
}
