package dev.dsh.client

import android.app.Application
import dsh.ui.AppState

class DshApplication : Application() {
    lateinit var platform: AndroidPlatform
        private set
    lateinit var appState: AppState
        private set

    override fun onCreate() {
        super.onCreate()
        platform = AndroidPlatform(this)
        appState = AppState(platform)
    }
}
