package com.ara.timer

import android.Manifest
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.core.app.ServiceCompat

/**
 * Foreground service that owns a running session: it keeps the process alive, shows the
 * ongoing notification, and starts and stops each 12 second ring.
 *
 * Everything goes through [evaluate], which advances the [Session] state machine to the
 * current time and then arms a wake-up for the next transition (the next ring starting,
 * or the current ring ending). Two wake-ups are armed for every transition:
 *  - an exact AlarmManager alarm, which wakes the phone from deep sleep (Doze);
 *  - an in-process Handler timer, for when the CPU is already awake.
 * Whichever arrives first does the work; the other then finds nothing to do.
 */
class TimerService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var ringer: RingPlayer
    private lateinit var ringWakeLock: PowerManager.WakeLock
    private lateinit var fallbackWakeLock: PowerManager.WakeLock
    private var inForeground = false

    /** ringStart of the ring whose sound is playing right now, or NOT_RINGING. */
    private var soundingRing = Session.NOT_RINGING

    private val tick = Runnable { evaluate() }

    override fun onCreate() {
        super.onCreate()
        instance = this
        ringer = RingPlayer(this)
        val powerManager = getSystemService(PowerManager::class.java)
        ringWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AraTimer:ring")
            .apply { setReferenceCounted(false) }
        fallbackWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AraTimer:fallback")
            .apply { setReferenceCounted(false) }
        Notifications.ensureChannel(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every start must promote the service to the foreground first, even if it is
        // about to stop again, or the system crashes the app.
        if (!enterForeground()) {
            // Not allowed to run right now. The pending exact alarm will retry.
            BridgeWakeLock.release()
            stopSelf()
            return START_NOT_STICKY
        }
        val session = TimerRepository.session.value
        when {
            !session.running -> shutdown()
            TimerController.isStale(this, session) -> TimerController.stop(this)
            else -> evaluate()
        }
        BridgeWakeLock.release()
        return if (TimerRepository.session.value.running) START_STICKY else START_NOT_STICKY
    }

    /** The exact alarm fired (main thread, inside the receiver's onReceive). */
    fun onAlarm() {
        // The alarm's own wake lock ends when onReceive returns; hold ours while working.
        ringWakeLock.acquire(TimerConfig.RING_DURATION_MS + WAKE_LOCK_MARGIN_MS)
        evaluate()
        if (soundingRing == Session.NOT_RINGING) ringWakeLock.releaseSafely()
    }

    /** Brings sound, schedule and notification in line with the session at this moment. */
    private fun evaluate() {
        val before = TimerRepository.session.value
        if (!before.running) {
            shutdown()
            return
        }
        var session = before.advance(SystemClock.elapsedRealtime())

        if (session.isRinging) {
            if (soundingRing != session.ringStart) {
                // A ring is starting, or resuming after our process was restarted mid-ring.
                ringer.stop()
                ringWakeLock.acquire(TimerConfig.RING_DURATION_MS + WAKE_LOCK_MARGIN_MS)
                ringer.start(vibrate = TimerRepository.vibrate.value)
                if (session.ringStart != before.ringStart) {
                    // A brand-new ring: its 12 seconds count from when the sound actually started.
                    session = session.copy(ringStart = SystemClock.elapsedRealtime())
                }
                soundingRing = session.ringStart
            }
        } else if (soundingRing != Session.NOT_RINGING) {
            // The 12 seconds are over: silence, and the countdown resumes.
            ringer.stop()
            soundingRing = Session.NOT_RINGING
            ringWakeLock.releaseSafely()
        }

        if (session != before) TimerRepository.saveSession(session)
        scheduleNext(session, SystemClock.elapsedRealtime())
        refreshNotification()
    }

    private fun scheduleNext(session: Session, now: Long) {
        val next = session.nextTransition
        val delay = (next - now).coerceAtLeast(0L)
        Alarms.schedule(this, next)
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, delay)
        if (Alarms.canScheduleExact(this)) {
            fallbackWakeLock.releaseSafely()
        } else {
            // Without exact alarms the phone may sleep through the transition, so keep the
            // CPU awake until just after it (renewed every time).
            fallbackWakeLock.acquire(delay + WAKE_LOCK_MARGIN_MS)
        }
    }

    /** Ends everything right away: sound, vibration, timers, wake locks and the service. */
    fun shutdown() {
        handler.removeCallbacksAndMessages(null)
        ringer.stop()
        soundingRing = Session.NOT_RINGING
        ringWakeLock.releaseSafely()
        fallbackWakeLock.releaseSafely()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        inForeground = false
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        ringer.stop()
        soundingRing = Session.NOT_RINGING
        ringWakeLock.releaseSafely()
        fallbackWakeLock.releaseSafely()
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun enterForeground(): Boolean = try {
        val notification = Notifications.build(
            this, TimerRepository.session.value, SystemClock.elapsedRealtime(),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                Notifications.ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(Notifications.ID, notification)
        }
        inForeground = true
        true
    } catch (e: Exception) {
        Log.w(TAG, "Could not enter the foreground", e)
        false
    }

    private fun refreshNotification() {
        if (!inForeground) return
        val session = TimerRepository.session.value
        if (!session.running) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        getSystemService(NotificationManager::class.java)?.notify(
            Notifications.ID, Notifications.build(this, session, SystemClock.elapsedRealtime()),
        )
    }

    @VisibleForTesting
    internal val ringPlayer: RingPlayer get() = ringer

    @VisibleForTesting
    internal val holdsRingWakeLock: Boolean get() = ringWakeLock.isHeld

    @VisibleForTesting
    internal val holdsFallbackWakeLock: Boolean get() = fallbackWakeLock.isHeld

    companion object {
        private const val TAG = "AraTimerService"
        private const val WAKE_LOCK_MARGIN_MS = 5_000L

        /** The running service, if any. Only touched on the main thread. */
        var instance: TimerService? = null
            private set
    }
}
