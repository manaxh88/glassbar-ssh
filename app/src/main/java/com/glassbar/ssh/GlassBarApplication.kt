package com.glassbar.ssh

import android.app.Application

lateinit var glassBarApp: GlassBarApplication

class GlassBarApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        glassBarApp = this
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            android.util.Log.e("GlassBarApp", "Uncaught exception on thread ${thread.name}", throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
