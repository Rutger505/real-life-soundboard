package com.rutger.soundboard.update

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.rutger.soundboard.AppLog
import com.rutger.soundboard.BuildConfig
import com.rutger.soundboard.MainActivity
import com.rutger.soundboard.SoundboardState
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/**
 * Self-update from the CDN (`update.json`, written by android/scripts/publish), like Fastify and
 * Workstation Connect:
 * - Check at process start, when the app comes to the foreground (throttled), from the menu, and every
 *   15 min from [UpdateWorker] (any network).
 * - Download + sha256 verify, then install silently via a PackageInstaller session. No install
 *   constraints: the BLE foreground service always counts as "foreground", so Android would wait forever.
 *   Own guard instead: not while a key was pressed in the last [IN_USE_MS]; the next check retries.
 * - The install kills the process; [PackageReplacedReceiver] restarts the service.
 * - Not silent ("install unknown apps" not granted, Android < 12, Android asks anyway): card + notification.
 */
object Updater {
    data class Release(val versionCode: Int, val versionName: String, val apk: String, val sha256: String, val size: Long) {
        val label get() = "$versionName ($versionCode)"

        companion object {
            fun parse(s: String) = JSONObject(s).run {
                Release(getInt("versionCode"), getString("versionName"), getString("apk"), getString("sha256"), getLong("size"))
            }
        }
    }

    sealed interface Status {
        data object Idle : Status
        data object Checking : Status
        data class UpToDate(val latest: Release) : Status
        data class Downloading(val release: Release, val bytes: Long) : Status
        data class Deferred(val release: Release) : Status
        data class NeedsTap(val release: Release) : Status
        data class Installing(val release: Release) : Status
        data class Failed(val message: String, val release: Release?) : Status
    }

    private const val TAG = "Update"
    private const val WORKER_INTERVAL_MIN = 15L
    private const val FOREGROUND_THROTTLE_MS = 60_000L
    private const val IN_USE_MS = 2 * 60_000L
    private const val RETRY_FAILED_MS = 60 * 60_000L
    private const val CHANNEL = "updates"
    private const val NOTIFICATION_ID = 7001
    private const val PREFS = "update"

    private val baseUrl = BuildConfig.UPDATE_URL.trimEnd('/')
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "soundboard-update").apply { isDaemon = true } }
    private lateinit var app: Application
    @Volatile private var lastFinished = 0L
    @Volatile private var failedInstall: Pair<Int, Long>? = null
    @Volatile var foreground = false

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status

    fun init(context: Application) {
        app = context
        logVersionChange()
        WorkManager.getInstance(app).enqueueUniquePeriodicWork(
            UpdateWorker.NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<UpdateWorker>(WORKER_INTERVAL_MIN, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build(),
        )
        worker.execute { check("start") }
    }

    fun onForeground() {
        foreground = true
        if (SystemClock.elapsedRealtime() - lastFinished > FOREGROUND_THROTTLE_MS) worker.execute { check("foreground") }
    }

    fun checkNow() = worker.execute { check("manual") }

    fun installNow() = worker.execute {
        val r = available() ?: return@execute AppLog.w(TAG, "install now: no update available")
        AppLog.i(TAG, "install now tapped (${r.label})")
        if (apkFile(r).exists()) commit(r, interactive = true) else check("install now")
    }

    /** Blocking, worker threads only. */
    @Synchronized
    fun check(trigger: String): String = try {
        checkLocked(trigger)
    } finally {
        lastFinished = SystemClock.elapsedRealtime()
    }

    private fun checkLocked(trigger: String): String {
        val before = _status.value
        if (before is Status.Downloading || before is Status.Installing) return describe()
        _status.value = Status.Checking
        val release = try {
            fetchRelease()
        } catch (e: Exception) {
            val msg = "check: ${e.javaClass.simpleName}: ${e.message}"
            // The worker runs every 15 min: log an offline error once, not every time.
            if ((before as? Status.Failed)?.message != msg) AppLog.w(TAG, "check ($trigger) failed", e)
            _status.value = Status.Failed(msg, null)
            return describe()
        }
        if (release.versionCode <= BuildConfig.VERSION_CODE) {
            if ((before as? Status.UpToDate)?.latest != release) AppLog.d(TAG, "check ($trigger): up to date, latest ${release.label}")
            dir().deleteRecursively()
            abandonSessions()
            _status.value = Status.UpToDate(release)
            return describe()
        }
        if (available(before) != release) AppLog.i(TAG, "check ($trigger): ${release.label} available")
        try {
            download(release)
        } catch (e: Exception) {
            AppLog.e(TAG, "download ${release.label} failed", e)
            _status.value = Status.Failed("download: ${e.javaClass.simpleName}: ${e.message}", release)
            return describe()
        }
        if (!canInstallSilently()) {
            if (before !is Status.NeedsTap) notifyNeedsTap(release)
            _status.value = Status.NeedsTap(release)
            return describe()
        }
        val failed = failedInstall
        if (failed != null && failed.first == release.versionCode && System.currentTimeMillis() - failed.second < RETRY_FAILED_MS) {
            _status.value = before
            return describe()
        }
        val sincePress = SystemClock.elapsedRealtime() - SoundboardState.lastPressAt.value
        if (SoundboardState.lastPressAt.value > 0 && sincePress < IN_USE_MS) {
            if (before !is Status.Deferred) AppLog.i(TAG, "${release.label} deferred: key pressed ${sincePress / 1000} s ago")
            _status.value = Status.Deferred(release)
            return describe()
        }
        commit(release, interactive = false)
        return describe()
    }

    @Synchronized
    private fun commit(r: Release, interactive: Boolean) {
        val file = apkFile(r)
        if (!file.exists()) {
            _status.value = Status.Failed("install: ${file.name} missing", r)
            return AppLog.e(TAG, "commit ${r.label}: ${file.name} missing")
        }
        AppLog.i(TAG, "installing ${r.label} (${file.length() / 1024} KiB), interactive=$interactive")
        try {
            abandonSessions()
            val installer = app.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(app.packageName)
                setSize(file.length())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    setPackageSource(PackageInstaller.PACKAGE_SOURCE_OTHER)
                }
            }
            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                session.openWrite("soundboard.apk", 0, file.length()).use { out ->
                    file.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val intent = Intent(app, UpdateReceiver::class.java)
                    .setAction(UpdateReceiver.ACTION_STATUS)
                    .putExtra(UpdateReceiver.EXTRA_INTERACTIVE, interactive)
                    .putExtra(UpdateReceiver.EXTRA_VERSION, r.versionCode)
                // PackageInstaller fills in the status extras, so the PendingIntent must be mutable.
                val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                _status.value = Status.Installing(r)
                session.commit(PendingIntent.getBroadcast(app, id, intent, mutable or PendingIntent.FLAG_UPDATE_CURRENT).intentSender)
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "commit ${r.label} failed", e)
            _status.value = Status.Failed("install: ${e.javaClass.simpleName}: ${e.message}", r)
            failedInstall = r.versionCode to System.currentTimeMillis()
        }
    }

    internal fun onInstallResult(code: Int, message: String?, confirm: Intent?, interactive: Boolean, versionCode: Int) {
        val r = available()?.takeIf { it.versionCode == versionCode }
        val label = r?.label ?: "versionCode $versionCode"
        when (code) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                AppLog.w(TAG, "$label needs confirmation (interactive=$interactive): $message")
                r?.let { _status.value = Status.NeedsTap(it) }
                if (interactive && confirm != null && foreground) {
                    app.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } else {
                    r?.let(::notifyNeedsTap)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> AppLog.i(TAG, "$label installed")
            else -> {
                AppLog.e(TAG, "$label install failed: status=$code $message")
                _status.value = Status.Failed("install failed ($code): $message", r)
                failedInstall = versionCode to System.currentTimeMillis()
            }
        }
    }

    fun available(s: Status = _status.value): Release? = when (s) {
        is Status.Downloading -> s.release
        is Status.Deferred -> s.release
        is Status.NeedsTap -> s.release
        is Status.Installing -> s.release
        is Status.Failed -> s.release
        else -> null
    }?.takeIf { it.versionCode > BuildConfig.VERSION_CODE }

    fun describe(): String {
        val state = when (val s = _status.value) {
            Status.Idle -> "not checked"
            Status.Checking -> "checking"
            is Status.UpToDate -> "up to date, latest ${s.latest.label}"
            is Status.Downloading -> "downloading ${s.release.label}: ${s.bytes / 1024}/${s.release.size / 1024} KiB"
            is Status.Deferred -> "${s.release.label} waits until the soundboard is idle"
            is Status.NeedsTap -> "${s.release.label} needs a tap"
            is Status.Installing -> "installing ${s.release.label}"
            is Status.Failed -> "FAILED ${s.message}"
        }
        return "${BuildConfig.VERSION_NAME}: $state; installer=${installerOfRecord()}, canRequestInstalls=${app.packageManager.canRequestPackageInstalls()}"
    }

    /**
     * Silent = self-update (UPDATE_PACKAGES_WITHOUT_USER_ACTION + USER_ACTION_NOT_REQUIRED, Android 12+),
     * also right after an adb/browser install, as long as "install unknown apps" is granted and no other
     * app (a store) claimed update ownership.
     */
    private fun canInstallSilently(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        if (!app.packageManager.canRequestPackageInstalls()) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        val owner = runCatching { app.packageManager.getInstallSourceInfo(app.packageName).updateOwnerPackageName }
            .onFailure { AppLog.w(TAG, "update owner lookup failed", it) }.getOrNull()
        return owner == null || owner == app.packageName
    }

    private fun installerOfRecord(): String? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        runCatching { app.packageManager.getInstallSourceInfo(app.packageName).installingPackageName }.getOrNull()
    } else {
        @Suppress("DEPRECATION")
        runCatching { app.packageManager.getInstallerPackageName(app.packageName) }.getOrNull()
    }

    private fun open(path: String) = (URL("$baseUrl/$path").openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 60_000
        setRequestProperty("Cache-Control", "no-cache")
    }

    private fun fetchRelease(): Release {
        val c = open("update.json")
        try {
            if (c.responseCode != 200) error("HTTP ${c.responseCode}")
            return Release.parse(c.inputStream.bufferedReader().use { it.readText() })
        } finally {
            c.disconnect()
        }
    }

    private fun dir() = File(app.cacheDir, "updates")
    private fun apkFile(r: Release) = File(dir(), "soundboard-${r.versionCode}-${r.sha256.take(12)}.apk")

    private fun abandonSessions() {
        val installer = app.packageManager.packageInstaller
        installer.mySessions.forEach { s ->
            runCatching { installer.abandonSession(s.sessionId) }.onFailure { AppLog.w(TAG, "abandon session ${s.sessionId}", it) }
        }
    }

    /** Downloads to .part and renames only after the sha256 matches, so an existing final file is verified. */
    private fun download(r: Release) {
        val target = apkFile(r)
        if (target.exists()) return
        AppLog.i(TAG, "downloading ${r.label} (${r.size / 1024} KiB)")
        dir().deleteRecursively()
        dir().mkdirs()
        val part = File(dir(), target.name + ".part")
        val digest = MessageDigest.getInstance("SHA-256")
        _status.value = Status.Downloading(r, 0)
        val c = open(r.apk)
        try {
            if (c.responseCode != 200) error("HTTP ${c.responseCode} for ${r.apk}")
            c.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        total += n
                        _status.value = Status.Downloading(r, total)
                    }
                }
            }
        } finally {
            c.disconnect()
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        if (sha != r.sha256.lowercase()) {
            part.delete()
            error("sha256 mismatch: $sha, expected ${r.sha256}")
        }
        if (!part.renameTo(target)) error("rename ${part.name} failed")
        AppLog.i(TAG, "${r.label} downloaded, sha256 ok")
    }

    private fun notifyNeedsTap(r: Release) {
        if (foreground) return
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            app, 2, Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Soundboard ${r.versionName} ready")
            .setContentText("Tap to install")
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(NOTIFICATION_ID, n) }.onFailure { AppLog.e(TAG, "needs-tap notification failed", it) }
    }

    private fun logVersionChange() {
        val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previous = p.getString("versionName", null)
        if (previous == BuildConfig.VERSION_NAME) return
        AppLog.i(TAG, "now running ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}), was ${previous ?: "?"}")
        p.edit().putString("versionName", BuildConfig.VERSION_NAME).apply()
        app.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }
}
