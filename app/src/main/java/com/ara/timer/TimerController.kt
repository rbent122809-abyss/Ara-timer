package com.ara.timer

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat

/** Entry points used by the screen, the notification's Stop button and the alarm. */
object TimerController {
    private const val TAG = "AraTimer"

    /** Starts a new session. Returns false if the timer could not be started. */
    fun start(context: Context): Boolean {
        val app = context.applicationContext
        val current = TimerRepository.session.value
        if (current.running) {
            if (!isStale(app, current)) {
                ensureService(app)
                return true
            }
            stop(app) // leftover from before a reboot or a force stop: start fresh
        }
        val session = Session.start(
            now = SystemClock.elapsedRealtime(),
            wallNow = System.currentTimeMillis(),
            bootCount = Device.bootCount(app),
        )
        TimerRepository.saveSession(session)
        Alarms.schedule(app, session.nextTransition)
        if (!startService(app)) {
            TimerRepository.clearSession()
            Alarms.cancel(app)
            return false
        }
        return true
    }

    /** Stops the session immediately, including a ring that is playing right now. */
    fun stop(context: Context) {
        val app = context.applicationContext
        TimerRepository.clearSession()
        Alarms.cancel(app)
        val service = TimerService.instance
        if (service != null) {
            service.shutdown()
        } else {
            app.getSystemService(NotificationManager::class.java)?.cancel(Notifications.ID)
        }
    }

    /**
     * Makes sure a saved, still-valid session has its service running, e.g. after the
     * process was killed. Called whenever the screen comes to the foreground.
     */
    fun ensureService(context: Context) {
        val session = TimerRepository.session.value
        if (!session.running) return
        if (isStale(context, session)) {
            stop(context)
            return
        }
        if (TimerService.instance == null) startService(context.applicationContext)
    }

    /** The exact alarm fired. Runs on the main thread inside the receiver's onReceive. */
    fun onAlarm(context: Context) {
        TimerService.instance?.let {
            it.onAlarm()
            return
        }
        // Our process had been killed; the alarm started it again.
        val session = TimerRepository.session.value
        if (!session.running) return
        if (isStale(context, session)) {
            stop(context)
            return
        }
        BridgeWakeLock.acquire(context)
        if (!startService(context.applicationContext)) {
            // Android refused to let us come back right now. Keep an alarm armed so the
            // session gets another chance instead of silently dying.
            Alarms.schedule(context, SystemClock.elapsedRealtime() + TimerConfig.INTERVAL_MS)
            BridgeWakeLock.release()
        }
    }

    fun setVibrate(enabled: Boolean) {
        TimerRepository.setVibrate(enabled)
    }

    /**
     * A saved session must not be resumed if the phone rebooted since (elapsedRealtime
     * started over) or if the user stopped the app from Android's settings or the
     * "Active apps" panel; both count as stopping the timer.
     */
    fun isStale(context: Context, session: Session): Boolean =
        session.bootCount != Device.bootCount(context) ||
            session.cycleStart > SystemClock.elapsedRealtime() ||
            Device.userStoppedAppSince(context, session.startWall)

    private fun startService(context: Context): Boolean = try {
        ContextCompat.startForegroundService(context, Intent(context, TimerService::class.java))
        true
    } catch (e: Exception) {
        // IllegalStateException / ForegroundServiceStartNotAllowedException when the
        // system refuses a start from the background.
        Log.w(TAG, "Could not start the timer service", e)
        false
    }
}
