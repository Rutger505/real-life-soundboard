package com.rutger.soundboard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat

/**
 * Foreground service that owns the BLE connection and audio playback.
 *
 * Because it runs in the foreground (with an ongoing notification), the
 * soundboard keeps working while the phone is used normally, screen off, or the
 * app UI is closed. The persistent notification shows the connection state and
 * the last button that was pressed.
 */
class SoundboardService : Service() {

    private lateinit var prefs: SharedPreferences
    private lateinit var audioPlayer: AudioPlayer
    private lateinit var bleManager: BleManager
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("soundboard_prefs", Context.MODE_PRIVATE)
        audioPlayer = AudioPlayer()

        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())

        bleManager = BleManager(
            context = this,
            onButtonPressed = { index -> onButtonPressed(index) },
            onConnectionStateChanged = { connected ->
                SoundboardState.bleConnected.value = connected
                updateNotification()
            },
        )
        bleManager.startScan()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PLAY) {
            val index = intent.getIntExtra(EXTRA_INDEX, -1)
            if (index in 0..8) onButtonPressed(index)
        }
        // Restart if killed by the system so the connection self-heals.
        return START_STICKY
    }

    private fun onButtonPressed(index: Int) {
        if (index !in 0..8) return
        SoundboardState.lastButton.value = index
        SoundboardState.activeButton.value = index
        updateNotification()

        val uriStr = prefs.getString("uri_$index", null)
        if (uriStr != null) {
            audioPlayer.play(this, Uri.parse(uriStr))
        }

        // Clear the transient "active" highlight after a short delay.
        handler.postDelayed({
            if (SoundboardState.activeButton.value == index) {
                SoundboardState.activeButton.value = null
            }
        }, 300)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Soundboard",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Keeps the ESP32 connection alive" }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val connected = SoundboardState.bleConnected.value
        val last = SoundboardState.lastButton.value

        val status = if (connected) "ESP32 connected" else "Reconnecting…"
        val lastText = last?.let { " • Last: button ${it + 1}" } ?: ""

        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Soundboard")
            .setContentText(status + lastText)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIF_ID, buildNotification())
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        bleManager.disconnect()
        audioPlayer.release()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "soundboard_service"
        private const val NOTIF_ID = 1
        const val ACTION_PLAY = "com.rutger.soundboard.action.PLAY"
        const val EXTRA_INDEX = "index"

        /** Start the service in the foreground (safe to call repeatedly). */
        fun start(context: Context) {
            val intent = Intent(context, SoundboardService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Ask the service to play the audio configured for [index]. */
        fun play(context: Context, index: Int) {
            val intent = Intent(context, SoundboardService::class.java).apply {
                action = ACTION_PLAY
                putExtra(EXTRA_INDEX, index)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
