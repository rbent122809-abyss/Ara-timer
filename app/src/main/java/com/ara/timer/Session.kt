package com.ara.timer

import java.util.Locale

object TimerConfig {
    /** Time between the start of one ring and the start of the next. */
    const val INTERVAL_MS = 5 * 60 * 1000L

    /** How long each ring lasts. */
    const val RING_DURATION_MS = 12_000L

    /** A trigger that fires a hair before the due time still counts as on time. */
    const val DUE_TOLERANCE_MS = 500L

    /**
     * If a ring is delivered late (phone was deeply asleep, app was restarted) it still
     * plays when it is at most this late. Anything later is skipped so a stale ring
     * never fires right before the next one.
     */
    const val MAX_LATE_MS = INTERVAL_MS / 2
}

/**
 * A cleaning session. Ring n (n >= 1) is due at [startElapsed] + n * interval, on the
 * [android.os.SystemClock.elapsedRealtime] clock, which keeps counting while the phone
 * sleeps and is not affected by changes to the wall clock or time zone.
 */
data class Session(
    val running: Boolean = false,
    /** elapsedRealtime() when Start was pressed. */
    val startElapsed: Long = 0L,
    /** System.currentTimeMillis() when Start was pressed, for showing clock times. */
    val startWall: Long = 0L,
    /** Settings.Global.BOOT_COUNT at start, to detect a reboot (elapsedRealtime resets). */
    val bootCount: Int = -1,
    /** Index of the last ring that was handled (played or skipped). 0 = none yet. */
    val lastIndex: Int = 0,
    /** Rings actually played this session. */
    val ringCount: Int = 0,
) {
    fun dueTime(index: Int): Long = startElapsed + index * TimerConfig.INTERVAL_MS

    /** Index of the most recent ring that is due at [now] (0 if none is due yet). */
    fun dueIndexAt(now: Long): Int {
        val sinceStart = now - startElapsed + TimerConfig.DUE_TOLERANCE_MS
        if (sinceStart <= 0) return 0
        return (sinceStart / TimerConfig.INTERVAL_MS).toInt()
    }

    /** When the next ring that has not been handled yet is due. */
    val nextDueTime: Long get() = dueTime(lastIndex + 1)

    fun elapsedMs(now: Long): Long = (now - startElapsed).coerceAtLeast(0L)

    /** Time left in the current 5 minute cycle, by the clock. Always in (0, INTERVAL]. */
    fun msUntilNextRing(now: Long): Long {
        val intoCycle = elapsedMs(now) % TimerConfig.INTERVAL_MS
        return TimerConfig.INTERVAL_MS - intoCycle
    }

    /** Wall-clock time of the next ring, derived from the start so it never jitters. */
    fun nextRingWallTime(now: Long): Long {
        val cycle = elapsedMs(now) / TimerConfig.INTERVAL_MS
        return startWall + (cycle + 1) * TimerConfig.INTERVAL_MS
    }

    companion object {
        val IDLE = Session()
    }
}

object TimeFormat {
    /** "04:32" – rounds up so the display reads 00:01 in the final second, never 00:00. */
    fun countdown(ms: Long): String {
        val totalSeconds = (ms.coerceAtLeast(0L) + 999L) / 1000L
        return String.format(Locale.ROOT, "%02d:%02d", totalSeconds / 60, totalSeconds % 60)
    }

    /** "7:05" or "1:02:33". */
    fun elapsed(ms: Long): String {
        val totalSeconds = ms.coerceAtLeast(0L) / 1000L
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
        }
    }
}
