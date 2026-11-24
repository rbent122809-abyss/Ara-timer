package com.ara.timer.ui

import com.ara.timer.Session
import com.ara.timer.TimeFormat
import com.ara.timer.TimerConfig

enum class Warning { Volume, Notifications, ExactAlarms, Battery }

data class TimerUiState(
    val running: Boolean,
    /** Ringing: the countdown and the elapsed time are paused. */
    val ringing: Boolean,
    /** Whole seconds until the ring ends and the timer resumes. */
    val ringSecondsLeft: Int,
    /** 0..1: how far through the current 5 minute countdown we are (1 while ringing). */
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
        /**
         * @param now SystemClock.elapsedRealtime()
         * @param wallClockOffset System.currentTimeMillis() - SystemClock.elapsedRealtime()
         * @param formatClock formats a System.currentTimeMillis() value as a clock time
         */
        fun from(
            session: Session,
            now: Long,
            wallClockOffset: Long,
            formatClock: (Long) -> String,
            ringtoneText: String,
            vibrate: Boolean,
            previewing: Boolean,
            warnings: List<Warning>,
        ): TimerUiState {
            if (!session.running) {
                return TimerUiState(
                    running = false,
                    ringing = false,
                    ringSecondsLeft = 0,
                    cycleFraction = 0f,
                    countdownText = TimeFormat.countdown(TimerConfig.INTERVAL_MS),
                    elapsedText = TimeFormat.elapsed(0L),
                    ringCount = 0,
                    nextAtText = null,
                    ringtoneText = ringtoneText,
                    vibrate = vibrate,
                    previewing = previewing,
                    warnings = warnings,
                )
            }
            val shown = session.displayAt(now)
            val countdown = shown.countdownMs(now)
            return TimerUiState(
                running = true,
                ringing = shown.isRinging,
                ringSecondsLeft = TimeFormat.secondsLeft(shown.ringMsLeft(now)),
                cycleFraction = if (shown.isRinging) 1f else 1f - countdown.toFloat() / TimerConfig.INTERVAL_MS,
                countdownText = TimeFormat.countdown(countdown),
                elapsedText = TimeFormat.elapsed(shown.activeMs(now)),
                ringCount = shown.rings,
                nextAtText = formatClock(shown.nextRingDue + wallClockOffset),
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
