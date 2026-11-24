package com.rutger.soundboard.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build

class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_STATUS) return
        Updater.onInstallResult(
            code = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE),
            message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE),
            confirm = confirmIntent(intent),
            interactive = intent.getBooleanExtra(EXTRA_INTERACTIVE, false),
            versionCode = intent.getIntExtra(EXTRA_VERSION, -1),
        )
    }

    private fun confirmIntent(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }

    companion object {
        const val ACTION_STATUS = "com.rutger.soundboard.update.STATUS"
        const val EXTRA_INTERACTIVE = "interactive"
        const val EXTRA_VERSION = "versionCode"
    }
}
