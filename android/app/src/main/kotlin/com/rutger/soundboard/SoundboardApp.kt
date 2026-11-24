package com.rutger.soundboard

import android.app.Application
import com.rutger.soundboard.update.Updater

class SoundboardApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        Updater.init(this)
    }
}
