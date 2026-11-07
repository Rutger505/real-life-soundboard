package com.rutger.soundboard

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import androidx.core.content.FileProvider
import java.io.File
import java.io.Writer
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Bundles state, battery history, the app log and this process's logcat into one file and shares it. */
object LogShare {
    private val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

    suspend fun share(context: Context) {
        val file = withContext(Dispatchers.IO) { collect(context) }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Soundboard logs")
            clipData = ClipData.newRawUri("Soundboard logs", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(send, "Share soundboard logs").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun collect(context: Context): File {
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val dir = File(context.cacheDir, "shared").apply {
            mkdirs()
            listFiles()?.forEach { it.delete() }
        }
        val out = File(dir, "soundboard-logs-$stamp.txt")
        out.bufferedWriter().use { w ->
            w.appendLine("Soundboard ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            w.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            w.appendLine("Collected: ${LocalDateTime.now()}")
            // From disk: the in-memory session is empty while the service isn't running.
            val session = BatteryHistory(context).load()
            state(w, session)
            batteryHistory(w, session)
            for (f in AppLog.files) {
                w.appendLine()
                w.appendLine("==== ${f.name} ====")
                w.append(f.readText())
            }
            w.appendLine()
            w.appendLine("==== logcat (this process) ====")
            runCatching {
                val p = ProcessBuilder("logcat", "-d", "-v", "threadtime", "--pid=" + Process.myPid())
                    .redirectErrorStream(true)
                    .start()
                p.inputStream.bufferedReader().useLines { lines -> lines.forEach { w.appendLine(it) } }
            }.onFailure { w.appendLine("logcat failed: $it") }
        }
        return out
    }

    private fun state(w: Writer, session: BatterySession) {
        val s = SoundboardState
        val reading = s.battery.value
        val stats = computeBatteryStats(session)
        w.appendLine()
        w.appendLine("==== state ====")
        w.appendLine("BLE connected: ${s.bleConnected.value}")
        w.appendLine(
            "Battery: " + (reading?.let {
                "${it.percent} %, ${it.millivolts ?: "-"} mV, charging ${it.charging} (${chargingLabel(it.charging)})"
            } ?: "none"),
        )
        w.appendLine("Session start: ${session.startMs?.let { time.format(Instant.ofEpochMilli(it)) } ?: "-"}")
        w.appendLine("Presses since charge: ${session.presses}")
        w.appendLine("Drain: ${stats.drainPercentPerHour?.let { "%.2f %%/h".format(it) } ?: "-"}")
    }

    private fun batteryHistory(w: Writer, session: BatterySession) {
        val samples = session.samples
        w.appendLine()
        w.appendLine("==== battery samples since charge (${samples.size}) ====")
        w.appendLine("time,percent,millivolts")
        for (s in samples) {
            w.appendLine("${time.format(Instant.ofEpochMilli(s.timeMs))},${s.percent},${s.millivolts ?: ""}")
        }
    }
}
