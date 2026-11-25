package com.example.steamfetch

import android.app.Application

class SteamFetchApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CryptoSetup.install()
        SteamManager.init(this)
    }
}
