package com.rutger.soundboard

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri

class AudioPlayer {
    private var mediaPlayer: MediaPlayer? = null

    fun play(context: Context, uri: Uri) {
        mediaPlayer?.let {
            if (it.isPlaying) it.stop()
            it.release()
        }
        mediaPlayer = MediaPlayer().apply {
            setDataSource(context, uri)
            prepare()
            start()
            setOnCompletionListener { release() }
        }
    }

    fun release() {
        mediaPlayer?.release()
        mediaPlayer = null
    }
}
