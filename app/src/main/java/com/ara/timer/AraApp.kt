package com.ara.timer

import android.app.Application

class AraApp : Application() {
    override fun onCreate() {
        super.onCreate()
        TimerRepository.init(this)
        Notifications.ensureChannel(this)
    }
}
