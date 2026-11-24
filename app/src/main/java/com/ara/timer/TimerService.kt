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
 * ongoing notification, schedules each ring and plays it for 12 seconds.
 *
 * Two triggers lead to a ring, and both funnel into [evaluate], which plays each ring at
 * most once:
 *  - an exact AlarmManager alarm, which wakes the phone from deep sleep (Doze);
 *  - an in-process Handler timer, which covers the time the CPU is already awake.
 */
class TimerService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var ringer: RingPlayer
    private lateinit var ringWakeLock: PowerManager.WakeLock
    private lateinit var fallbackWakeLock: PowerManager.WakeLock
    private var ringing = false
    private var inForeground = false

    private val tick = Runnable { evaluate() }
    private val endRing = Runnable { finishRing() }

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
        if (!ringing) ringWakeLock.releaseSafely()
    }

    /** Plays the ring if one is due, then schedules the next one. */
    private fun evaluate() {
        val session = TimerRepository.session.value
        if (!session.running) {
            shutdown()
            return
        }
        val now = SystemClock.elapsedRealtime()
        val dueIndex = session.dueIndexAt(now)
        var current = session
        if (dueIndex > session.lastIndex) {
            val lateBy = now - session.dueTime(dueIndex)
            val playIt = lateBy <= TimerConfig.MAX_LATE_MS && !ringing
            current = session.copy(
                lastIndex = dueIndex,
                ringCount = session.ringCount + if (playIt) 1 else 0,
            )
            TimerRepository.saveSession(current)
            if (playIt) startRing()
        }
        scheduleNext(current, now)
        refreshNotification()
    }

    private fun scheduleNext(session: Session, now: Long) {
        val next = session.nextDueTime
        Alarms.schedule(this, next)
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, (next - now).coerceAtLeast(0L))
        if (Alarms.canScheduleExact(this)) {
            fallbackWakeLock.releaseSafely()
        } else {
            // Without exact alarms the phone may sleep through the ring, so keep the CPU
            // awake until just after it (renewed every cycle).
            fallbackWakeLock.acquire(next - now + WAKE_LOCK_MARGIN_MS)
        }
    }

    private fun startRing() {
        ringing = true
        ringWakeLock.acquire(TimerConfig.RING_DURATION_MS + WAKE_LOCK_MARGIN_MS)
        ringer.start(vibrate = TimerRepository.vibrate.value)
        TimerRepository.setRingingUntil(SystemClock.elapsedRealtime() + TimerConfig.RING_DURATION_MS)
        handler.removeCallbacks(endRing)
        handler.postDelayed(endRing, TimerConfig.RING_DURATION_MS)
    }

    private fun finishRing() {
        handler.removeCallbacks(endRing)
        ringer.stop()
        ringing = false
        TimerRepository.setRingingUntil(0L)
        ringWakeLock.releaseSafely()
        if (TimerRepository.session.value.running) refreshNotification()
    }

    /** Ends everything right away: sound, vibration, timers, wake locks and the service. */
    fun shutdown() {
        handler.removeCallbacksAndMessages(null)
        ringer.stop()
        ringing = false
        TimerRepository.setRingingUntil(0L)
        ringWakeLock.releaseSafely()
        fallbackWakeLock.releaseSafely()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        inForeground = false
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        ringer.stop()
        ringing = false
        TimerRepository.setRingingUntil(0L)
        ringWakeLock.releaseSafely()
        fallbackWakeLock.releaseSafely()
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun enterForeground(): Boolean = try {
        val notification = Notifications.build(this, TimerRepository.session.value, ringing)
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
        getSystemService(NotificationManager::class.java)
            ?.notify(Notifications.ID, Notifications.build(this, session, ringing))
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
