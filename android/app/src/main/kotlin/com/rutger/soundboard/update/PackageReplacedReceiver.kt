package com.rutger.soundboard.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.rutger.soundboard.AppLog
import com.rutger.soundboard.SoundboardService

/**
 * An update kills the process and Android doesn't restart a sticky service after it. This broadcast
 * is exempt from the background foreground-service start ban, so the soundboard reconnects on its own.
 */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        AppLog.i("Update", "package replaced, restarting service")
        SoundboardService.start(context)
    }
}
