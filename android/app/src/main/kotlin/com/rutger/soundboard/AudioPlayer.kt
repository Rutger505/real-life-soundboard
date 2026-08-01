package com.rutger.soundboard

import android.content.Context
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

    /** Prepare (or re-prepare) the audio for [index]. Safe to call repeatedly. */
    @Synchronized
    fun preload(context: Context, index: Int, uri: Uri) {
        slots.remove(index)?.player?.let {
            if (it == current) current = null
            it.release()
        }
        try {
            val mp = MediaPlayer()
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
        // One sound at a time: stop whatever is currently playing.
        current?.let { if (it != slot.player && it.isPlaying) it.pause() }
        slot.player.seekTo(0)
        slot.player.start()
        current = slot.player
    }

    @Synchronized
    fun release() {
        slots.values.forEach { it.player.release() }
        slots.clear()
        current = null
    }
}
