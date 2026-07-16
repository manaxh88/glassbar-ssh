package com.glassbar.ssh

import android.app.Application

lateinit var glassBarApp: GlassBarApplication

class GlassBarApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        glassBarApp = this
    }
}
