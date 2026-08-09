package com.rutger.soundboard

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log

/**
 * Low-latency player for the soundboard.
 *
 * Each slot keeps its own [MediaPlayer] that is prepared ahead of time (async,
 * off the main thread) via [preload]. Pressing a button then only calls
 * `seekTo(0) + start()`, which is effectively instant — no per-press
 * `setDataSource`/`prepare` I/O stall.
 */
class AudioPlayer {

    private class Slot(val player: MediaPlayer, @Volatile var ready: Boolean)

    private val slots = HashMap<Int, Slot>()
    private var current: MediaPlayer? = null

    /** App context, captured on first [preload], used to force the media volume. */
    private var appContext: Context? = null

    private val mediaAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    /** Prepare (or re-prepare) the audio for [index]. Safe to call repeatedly. */
    @Synchronized
    fun preload(context: Context, index: Int, uri: Uri) {
        if (appContext == null) appContext = context.applicationContext
        slots.remove(index)?.player?.let {
            if (it == current) current = null
            it.release()
        }
        try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(mediaAttributes)
            val slot = Slot(mp, ready = false)
            mp.setOnPreparedListener { slot.ready = true }
            mp.setOnErrorListener { _, what, extra ->
                Log.e("AudioPlayer", "MediaPlayer error slot $index: $what/$extra")
                slot.ready = false
                true
            }
            mp.setDataSource(context, uri)
            mp.prepareAsync() // non-blocking; readiness flips in the listener
            slots[index] = slot
        } catch (e: Exception) {
            Log.e("AudioPlayer", "Failed to preload slot $index", e)
        }
    }

    /** Drop the preloaded audio for [index] (e.g. slot cleared). */
    @Synchronized
    fun clear(index: Int) {
        slots.remove(index)?.player?.let {
            if (it == current) current = null
            it.release()
        }
    }

    /** Play slot [index] from the start. Instant when preloaded. */
    @Synchronized
    fun play(index: Int) {
        val slot = slots[index]
        if (slot == null || !slot.ready) {
            Log.w("AudioPlayer", "Slot $index not ready")
            return
        }
        // Force the media stream to max volume before playing, so the sound
        // comes out loud even if the phone's media volume is turned down or
        // the ringer is silenced (media is a separate stream from ring/notif).
        forceMediaVolumeMax()
        // One sound at a time: stop whatever is currently playing.
        current?.let { if (it != slot.player && it.isPlaying) it.pause() }
        slot.player.seekTo(0)
        slot.player.start()
        current = slot.player
    }

    /** Crank STREAM_MUSIC to its maximum. No-op if we have no context yet. */
    private fun forceMediaVolumeMax() {
        val ctx = appContext ?: return
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            if (am.getStreamVolume(AudioManager.STREAM_MUSIC) != max) {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, max, 0)
            }
        } catch (e: Exception) {
            Log.w("AudioPlayer", "Failed to set media volume to max", e)
        }
    }

    @Synchronized
    fun release() {
        slots.values.forEach { it.player.release() }
        slots.clear()
        current = null
    }
}
