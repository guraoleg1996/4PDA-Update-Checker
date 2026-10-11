package com.example.a4pdaupdatechecker

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.attach(this)
    }
}
