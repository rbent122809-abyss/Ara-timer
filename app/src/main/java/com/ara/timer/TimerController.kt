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
        if (TimerRepository.session.value.running) {
            ensureService(app)
            return true
        }
        val session = Session(
            running = true,
            startElapsed = SystemClock.elapsedRealtime(),
            startWall = System.currentTimeMillis(),
            bootCount = Device.bootCount(app),
        )
        TimerRepository.saveSession(session)
        Alarms.schedule(app, session.nextDueTime)
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
        // Our process was killed and restarted by the alarm.
        val session = TimerRepository.session.value
        if (!session.running) return
        if (isStale(context, session)) {
            stop(context)
            return
        }
        BridgeWakeLock.acquire(context)
        if (!startService(context.applicationContext)) {
            // Could not come back to the foreground right now; keep the chain of alarms
            // alive so the next ring gets another chance.
            val now = SystemClock.elapsedRealtime()
            Alarms.schedule(context, session.dueTime(session.dueIndexAt(now) + 1))
            BridgeWakeLock.release()
        }
    }

    fun setVibrate(enabled: Boolean) {
        TimerRepository.setVibrate(enabled)
    }

    /** A session saved before a reboot can't be resumed: elapsedRealtime started over. */
    fun isStale(context: Context, session: Session): Boolean =
        session.bootCount != Device.bootCount(context) ||
            session.startElapsed > SystemClock.elapsedRealtime()

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
