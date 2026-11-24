package com.ara.timer

import java.util.Locale

object TimerConfig {
    /** Length of each countdown. When it reaches zero the phone rings. */
    const val INTERVAL_MS = 5 * 60 * 1000L

    /** How long each ring lasts. The timer is paused for this long. */
    const val RING_DURATION_MS = 12_000L
}

/**
 * A cleaning session: a 5 minute countdown, then a 12 second ring during which the
 * timer is paused, then the next 5 minute countdown starts once the ring has ended.
 *
 *     |---- 5:00 counting ----|-- 12 s ring --|---- 5:00 counting ----|-- 12 s ring --| ...
 *     cycleStart          ringStart        ringEnd = next cycleStart
 *
 * All times are [android.os.SystemClock.elapsedRealtime] values: that clock keeps
 * counting while the phone sleeps and ignores wall-clock and time zone changes.
 */
data class Session(
    val running: Boolean = false,
    /** Settings.Global.BOOT_COUNT at start, to detect a reboot (elapsedRealtime resets). */
    val bootCount: Int = -1,
    /** System.currentTimeMillis() at Start, to tell whether the user force-stopped the app since. */
    val startWall: Long = 0L,
    /** When the current 5 minute countdown began: at Start, or when the last ring ended. */
    val cycleStart: Long = 0L,
    /** When the current ring began, or [NOT_RINGING] while counting down. */
    val ringStart: Long = NOT_RINGING,
    /** Rings so far. Each one marks a completed 5 minute countdown. */
    val rings: Int = 0,
) {
    val isRinging: Boolean get() = ringStart != NOT_RINGING

    /** While counting: when the countdown reaches zero and the phone should ring. */
    val ringDue: Long get() = cycleStart + TimerConfig.INTERVAL_MS

    /** While ringing: when the ring stops and the timer resumes. */
    val ringEnd: Long get() = ringStart + TimerConfig.RING_DURATION_MS

    /** The next moment something has to happen (a ring starts or ends). */
    val nextTransition: Long get() = if (isRinging) ringEnd else ringDue

    /** When the next ring will start, assuming the current ring (if any) ends on time. */
    val nextRingDue: Long get() = if (isRinging) ringEnd + TimerConfig.INTERVAL_MS else ringDue

    /**
     * Moves the state machine forward to [now]: ends a ring whose 12 seconds are over
     * (the countdown resumes from exactly that moment) and starts a ring whose countdown
     * has reached zero (from [now], the moment it actually starts).
     *
     * A ring that is late, e.g. because the app had been killed, still plays once, and the
     * next countdown starts after it. Missed time is never "caught up" with extra rings.
     */
    fun advance(now: Long): Session {
        var session = this
        if (session.isRinging && now >= session.ringEnd) {
            session = session.copy(cycleStart = session.ringEnd, ringStart = NOT_RINGING)
        }
        if (!session.isRinging && now >= session.ringDue) {
            session = session.copy(ringStart = now, rings = session.rings + 1)
        }
        return session
    }

    /**
     * The session as the screen should show it at [now]. A ring whose 12 seconds are over
     * is shown as ended even if the service hasn't processed it yet. A ring that is due but
     * hasn't started yet is NOT shown as ringing: the countdown just sits at 00:00.
     */
    fun displayAt(now: Long): Session =
        if (isRinging && now >= ringEnd) copy(cycleStart = ringEnd, ringStart = NOT_RINGING) else this

    /** Countdown to the next ring. Frozen at 0 while ringing. */
    fun countdownMs(now: Long): Long =
        if (isRinging) 0L else (ringDue - now).coerceIn(0L, TimerConfig.INTERVAL_MS)

    /** Timer time: 5:00 for each completed countdown plus the current one. Paused while ringing. */
    fun activeMs(now: Long): Long =
        rings * TimerConfig.INTERVAL_MS +
            if (isRinging) 0L else (now - cycleStart).coerceIn(0L, TimerConfig.INTERVAL_MS)

    /** Time left in the current ring, or 0 while counting down. */
    fun ringMsLeft(now: Long): Long =
        if (isRinging) (ringEnd - now).coerceIn(0L, TimerConfig.RING_DURATION_MS) else 0L

    companion object {
        const val NOT_RINGING = -1L
        val IDLE = Session()

        fun start(now: Long, wallNow: Long, bootCount: Int) = Session(
            running = true,
            bootCount = bootCount,
            startWall = wallNow,
            cycleStart = now,
        )
    }
}

object TimeFormat {
    /** "04:32". Rounds up, so it reads 05:00 at the start and 00:01 in the final second. */
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

    /** Whole seconds, rounded up: 12 s, 11 s ... 1 s. */
    fun secondsLeft(ms: Long): Int = ((ms.coerceAtLeast(0L) + 999L) / 1000L).toInt()
}
