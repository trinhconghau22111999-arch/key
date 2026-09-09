package com.viettype.smartkey

import android.app.Application

class KeyboardApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLogger.install(this)
    }
}
