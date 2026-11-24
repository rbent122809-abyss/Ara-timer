package com.ara.timer

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single source of truth for the timer, shared by the screen, the service and the
 * receiver (all on the main thread). The session is written through to SharedPreferences
 * so it survives the app's process being killed.
 */
object TimerRepository {
    private const val PREFS_NAME = "ara_timer"

    // Session (format 2: countdown pauses while ringing).
    private const val KEY_RUNNING = "s2_running"
    private const val KEY_BOOT_COUNT = "s2_boot_count"
    private const val KEY_START_WALL = "s2_start_wall"
    private const val KEY_CYCLE_START = "s2_cycle_start"
    private const val KEY_RING_START = "s2_ring_start"
    private const val KEY_RINGS = "s2_rings"

    // Settings.
    private const val KEY_VIBRATE = "vibrate"
    private const val KEY_NOTIFICATION_ASKED = "notification_permission_asked"
    private const val KEY_BATTERY_TIP_DISMISSED = "battery_tip_dismissed"

    /** Session keys written by version 1.0, whose schedule didn't pause while ringing. */
    private val LEGACY_KEYS = listOf(
        "running", "start_elapsed", "start_wall", "boot_count", "last_index", "ring_count",
    )

    private lateinit var prefs: SharedPreferences

    private val _session = MutableStateFlow(Session.IDLE)
    val session: StateFlow<Session> = _session.asStateFlow()

    private val _vibrate = MutableStateFlow(true)
    val vibrate: StateFlow<Boolean> = _vibrate.asStateFlow()

    private val _batteryTipDismissed = MutableStateFlow(false)
    val batteryTipDismissed: StateFlow<Boolean> = _batteryTipDismissed.asStateFlow()

    /** Called from Application.onCreate, before any other component runs. */
    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (LEGACY_KEYS.any { prefs.contains(it) }) {
            // A session from the old version can't be resumed; drop it.
            prefs.edit(commit = true) { LEGACY_KEYS.forEach { remove(it) } }
        }
        _session.value = readSession()
        _vibrate.value = prefs.getBoolean(KEY_VIBRATE, true)
        _batteryTipDismissed.value = prefs.getBoolean(KEY_BATTERY_TIP_DISMISSED, false)
    }

    // Session changes use commit = true (two tiny writes per cycle) so the state is on
    // disk even if the process is killed right after.
    fun saveSession(session: Session) {
        prefs.edit(commit = true) {
            putBoolean(KEY_RUNNING, session.running)
            putInt(KEY_BOOT_COUNT, session.bootCount)
            putLong(KEY_START_WALL, session.startWall)
            putLong(KEY_CYCLE_START, session.cycleStart)
            putLong(KEY_RING_START, session.ringStart)
            putInt(KEY_RINGS, session.rings)
        }
        _session.value = session
    }

    fun clearSession() {
        prefs.edit(commit = true) {
            remove(KEY_RUNNING)
            remove(KEY_BOOT_COUNT)
            remove(KEY_START_WALL)
            remove(KEY_CYCLE_START)
            remove(KEY_RING_START)
            remove(KEY_RINGS)
        }
        _session.value = Session.IDLE
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
            bootCount = prefs.getInt(KEY_BOOT_COUNT, -1),
            startWall = prefs.getLong(KEY_START_WALL, 0L),
            cycleStart = prefs.getLong(KEY_CYCLE_START, 0L),
            ringStart = prefs.getLong(KEY_RING_START, Session.NOT_RINGING),
            rings = prefs.getInt(KEY_RINGS, 0),
        )
    }
}
