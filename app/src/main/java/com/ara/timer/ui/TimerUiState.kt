package com.ara.timer.ui

import com.ara.timer.Session
import com.ara.timer.TimeFormat
import com.ara.timer.TimerConfig

enum class Warning { Volume, Notifications, ExactAlarms, Battery }

data class TimerUiState(
    val running: Boolean,
    val ringing: Boolean,
    /** Whole seconds left in the current ring. */
    val ringSecondsLeft: Int,
    /** 0..1, how much of the current ring has played. */
    val ringFraction: Float,
    /** 0..1, how far through the current 5 minute cycle we are. */
    val cycleFraction: Float,
    val countdownText: String,
    val elapsedText: String,
    val ringCount: Int,
    /** Clock time of the next ring, or null when idle. */
    val nextAtText: String?,
    val ringtoneText: String,
    val vibrate: Boolean,
    val previewing: Boolean,
    val warnings: List<Warning>,
) {
    companion object {
        fun from(
            session: Session,
            ringingUntil: Long,
            now: Long,
            formatClock: (Long) -> String,
            ringtoneText: String,
            vibrate: Boolean,
            previewing: Boolean,
            warnings: List<Warning>,
        ): TimerUiState {
            val running = session.running
            val ringMsLeft = if (running) (ringingUntil - now).coerceIn(0L, TimerConfig.RING_DURATION_MS) else 0L
            val ringing = ringMsLeft > 0L
            val untilNext = if (running) session.msUntilNextRing(now) else TimerConfig.INTERVAL_MS
            return TimerUiState(
                running = running,
                ringing = ringing,
                ringSecondsLeft = ((ringMsLeft + 999L) / 1000L).toInt(),
                ringFraction = 1f - ringMsLeft.toFloat() / TimerConfig.RING_DURATION_MS,
                cycleFraction = if (running) 1f - untilNext.toFloat() / TimerConfig.INTERVAL_MS else 0f,
                countdownText = TimeFormat.countdown(untilNext),
                elapsedText = TimeFormat.elapsed(if (running) session.elapsedMs(now) else 0L),
                ringCount = if (running) session.ringCount else 0,
                nextAtText = if (running) formatClock(session.nextRingWallTime(now)) else null,
                ringtoneText = ringtoneText,
                vibrate = vibrate,
                previewing = previewing,
                warnings = warnings,
            )
        }
    }
}

class TimerActions(
    val onStart: () -> Unit,
    val onStop: () -> Unit,
    val onVibrateChange: (Boolean) -> Unit,
    val onTogglePreview: () -> Unit,
    val onWarningAction: (Warning) -> Unit,
    val onWarningDismiss: (Warning) -> Unit,
) {
    companion object {
        val NoOp = TimerActions({}, {}, {}, {}, {}, {})
    }
}
