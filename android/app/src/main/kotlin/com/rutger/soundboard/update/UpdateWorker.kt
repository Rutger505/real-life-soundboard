package com.rutger.soundboard.update

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.rutger.soundboard.AppLog

class UpdateWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        runCatching { Updater.check("worker") }
            .onSuccess { AppLog.d("Update", "worker: $it") }
            .onFailure { AppLog.e("Update", "worker: check threw", it) }
        return Result.success()
    }

    companion object {
        const val NAME = "soundboard-update-check"
    }
}
