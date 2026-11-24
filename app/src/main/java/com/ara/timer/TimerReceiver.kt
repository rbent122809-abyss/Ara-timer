package com.ara.timer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Receives the exact ring alarm and the notification's Stop button. */
class TimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_ALARM -> TimerController.onAlarm(context)
            ACTION_STOP -> TimerController.stop(context)
        }
    }

    companion object {
        const val ACTION_ALARM = "com.ara.timer.action.RING_ALARM"
        const val ACTION_STOP = "com.ara.timer.action.STOP"
    }
}
