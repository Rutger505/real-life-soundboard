package com.rutger.soundboard

import android.content.Context
import android.util.Log
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

/**
 * Logcat + a small rotating file in filesDir/logs, so logs from earlier runs
 * (and crashes) survive restarts and can be shared from the app ([LogShare]).
 */
object AppLog {
    private const val MAX_BYTES = 512 * 1024
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "soundboard-log").apply { isDaemon = true } }
    private val time = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS")
    @Volatile private var file: File? = null

    /** Safe to call more than once; only the first call does anything. */
    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        val dir = File(context.filesDir, "logs").apply { mkdirs() }
        file = File(dir, "app.log")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            // Write synchronously: the process is about to die.
            write(line("E", "Crash", "uncaught on ${thread.name}: " + Log.getStackTraceString(e)))
            previous?.uncaughtException(thread, e)
        }
        i("App", "---- process start, v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ----")
    }

    /** Oldest first. */
    val files: List<File>
        get() = file?.let { listOf(File(it.parentFile, "app.log.1"), it) }.orEmpty().filter { it.exists() }

    fun d(tag: String, msg: String) {
        Log.d(tag, msg)
        async("D", tag, msg)
    }

    fun i(tag: String, msg: String) {
        Log.i(tag, msg)
        async("I", tag, msg)
    }

    fun w(tag: String, msg: String, e: Throwable? = null) {
        Log.w(tag, msg, e)
        async("W", tag, withTrace(msg, e))
    }

    fun e(tag: String, msg: String, e: Throwable? = null) {
        Log.e(tag, msg, e)
        async("E", tag, withTrace(msg, e))
    }

    private fun withTrace(msg: String, e: Throwable?) =
        msg + (e?.let { "\n" + Log.getStackTraceString(it) } ?: "")

    private fun line(level: String, tag: String, msg: String) =
        "${LocalDateTime.now().format(time)} $level/$tag: $msg"

    private fun async(level: String, tag: String, msg: String) {
        val l = line(level, tag, msg)
        writer.execute { write(l) }
    }

    @Synchronized
    private fun write(line: String) {
        val f = file ?: return
        runCatching {
            if (f.length() > MAX_BYTES) f.renameTo(File(f.parentFile, "app.log.1"))
            f.appendText(line + "\n")
        }
    }
}
