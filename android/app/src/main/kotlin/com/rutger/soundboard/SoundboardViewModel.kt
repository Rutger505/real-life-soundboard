package com.rutger.soundboard

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SoundEntry(
    val id: Int,
    val uri: Uri? = null,
    val displayName: String? = null,
)

class SoundboardViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs: SharedPreferences =
        application.getSharedPreferences("soundboard_prefs", Context.MODE_PRIVATE)

    private val audioPlayer = AudioPlayer()

    private val _sounds = MutableStateFlow(
        List(9) { i -> loadEntry(i) }
    )
    val sounds: StateFlow<List<SoundEntry>> = _sounds.asStateFlow()

    private val _bleConnected = MutableStateFlow(false)
    val bleConnected: StateFlow<Boolean> = _bleConnected.asStateFlow()

    private val _activeButton = MutableStateFlow<Int?>( null)
    val activeButton: StateFlow<Int?> = _activeButton.asStateFlow()

    private val bleManager = BleManager(
        context = application,
        onButtonPressed = { index -> onButtonPressed(index) },
        onConnectionStateChanged = { connected -> _bleConnected.value = connected },
    )

    init {
        bleManager.startScan()
    }

    fun setAudioFile(id: Int, uri: Uri) {
        val context = getApplication<Application>()
        context.contentResolver.takePersistableUriPermission(
            uri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
        val name = queryDisplayName(uri)
        prefs.edit()
            .putString("uri_$id", uri.toString())
            .putString("name_$id", name)
            .apply()
        val updated = _sounds.value.toMutableList()
        updated[id] = SoundEntry(id, uri, name)
        _sounds.value = updated
    }

    fun onButtonPressed(index: Int) {
        if (index !in 0..8) return
        _activeButton.value = index
        val entry = _sounds.value.getOrNull(index)
        val uri = entry?.uri ?: return
        val context = getApplication<Application>()
        audioPlayer.play(context, uri)
        // Clear active after short delay (UI feedback)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (_activeButton.value == index) _activeButton.value = null
        }, 300)
    }

    private fun loadEntry(id: Int): SoundEntry {
        val uriStr = prefs.getString("uri_$id", null) ?: return SoundEntry(id)
        val name = prefs.getString("name_$id", null)
        return SoundEntry(id, Uri.parse(uriStr), name)
    }

    private fun queryDisplayName(uri: Uri): String? {
        val context = getApplication<Application>()
        return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) cursor.getString(nameIndex) else null
        }
    }

    override fun onCleared() {
        super.onCleared()
        bleManager.disconnect()
        audioPlayer.release()
    }
}
