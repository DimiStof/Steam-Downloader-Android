package com.example.steamfetch

import android.app.Application

class SteamFetchApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SteamManager.init(this)
    }
}
