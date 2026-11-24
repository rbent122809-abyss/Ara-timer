package com.ara.timer

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single source of truth for the timer, shared by the screen, the service and the
 * alarm receiver (all on the main thread). The session is written through to
 * SharedPreferences so it survives the process being killed.
 */
object TimerRepository {
    private const val PREFS_NAME = "ara_timer"
    private const val KEY_RUNNING = "running"
    private const val KEY_START_ELAPSED = "start_elapsed"
    private const val KEY_START_WALL = "start_wall"
    private const val KEY_BOOT_COUNT = "boot_count"
    private const val KEY_LAST_INDEX = "last_index"
    private const val KEY_RING_COUNT = "ring_count"
    private const val KEY_VIBRATE = "vibrate"
    private const val KEY_NOTIFICATION_ASKED = "notification_permission_asked"
    private const val KEY_BATTERY_TIP_DISMISSED = "battery_tip_dismissed"

    private lateinit var prefs: SharedPreferences

    private val _session = MutableStateFlow(Session.IDLE)
    val session: StateFlow<Session> = _session.asStateFlow()

    /** elapsedRealtime() at which the current ring ends, or 0 when not ringing. */
    private val _ringingUntil = MutableStateFlow(0L)
    val ringingUntil: StateFlow<Long> = _ringingUntil.asStateFlow()

    private val _vibrate = MutableStateFlow(true)
    val vibrate: StateFlow<Boolean> = _vibrate.asStateFlow()

    private val _batteryTipDismissed = MutableStateFlow(false)
    val batteryTipDismissed: StateFlow<Boolean> = _batteryTipDismissed.asStateFlow()

    /** Called from Application.onCreate, before any other component runs. */
    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _session.value = readSession()
        _ringingUntil.value = 0L
        _vibrate.value = prefs.getBoolean(KEY_VIBRATE, true)
        _batteryTipDismissed.value = prefs.getBoolean(KEY_BATTERY_TIP_DISMISSED, false)
    }

    // Session changes use commit = true (a few tiny writes per 5 minutes) so the state is
    // on disk even if the process is killed right after.
    fun saveSession(session: Session) {
        prefs.edit(commit = true) {
            putBoolean(KEY_RUNNING, session.running)
            putLong(KEY_START_ELAPSED, session.startElapsed)
            putLong(KEY_START_WALL, session.startWall)
            putInt(KEY_BOOT_COUNT, session.bootCount)
            putInt(KEY_LAST_INDEX, session.lastIndex)
            putInt(KEY_RING_COUNT, session.ringCount)
        }
        _session.value = session
    }

    fun clearSession() {
        prefs.edit(commit = true) {
            remove(KEY_RUNNING)
            remove(KEY_START_ELAPSED)
            remove(KEY_START_WALL)
            remove(KEY_BOOT_COUNT)
            remove(KEY_LAST_INDEX)
            remove(KEY_RING_COUNT)
        }
        _session.value = Session.IDLE
        _ringingUntil.value = 0L
    }

    fun setRingingUntil(elapsedRealtime: Long) {
        _ringingUntil.value = elapsedRealtime
    }

    fun setVibrate(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_VIBRATE, enabled) }
        _vibrate.value = enabled
    }

    fun dismissBatteryTip() {
        prefs.edit { putBoolean(KEY_BATTERY_TIP_DISMISSED, true) }
        _batteryTipDismissed.value = true
    }

    var notificationPermissionAsked: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATION_ASKED, false)
        set(value) {
            prefs.edit { putBoolean(KEY_NOTIFICATION_ASKED, value) }
        }

    private fun readSession(): Session {
        if (!prefs.getBoolean(KEY_RUNNING, false)) return Session.IDLE
        return Session(
            running = true,
            startElapsed = prefs.getLong(KEY_START_ELAPSED, 0L),
            startWall = prefs.getLong(KEY_START_WALL, 0L),
            bootCount = prefs.getInt(KEY_BOOT_COUNT, -1),
            lastIndex = prefs.getInt(KEY_LAST_INDEX, 0),
            ringCount = prefs.getInt(KEY_RING_COUNT, 0),
        )
    }
}
