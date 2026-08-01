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

/**
 * Owns the per-slot audio configuration (persisted in prefs) and exposes the
 * connection/playback state that lives in [SoundboardService] via
 * [SoundboardState]. Playback + BLE run in the service, not here, so they keep
 * working when this ViewModel/Activity is gone.
 */
class SoundboardViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs: SharedPreferences =
        application.getSharedPreferences("soundboard_prefs", Context.MODE_PRIVATE)

    private val _sounds = MutableStateFlow(
        List(9) { i -> loadEntry(i) }
    )
    val sounds: StateFlow<List<SoundEntry>> = _sounds.asStateFlow()

    // Connection + playback state come straight from the service.
    val bleConnected: StateFlow<Boolean> = SoundboardState.bleConnected.asStateFlow()
    val activeButton: StateFlow<Int?> = SoundboardState.activeButton.asStateFlow()

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

    /** Manual press from the UI — routed through the service so it owns playback. */
    fun onButtonPressed(index: Int) {
        if (index !in 0..8) return
        SoundboardService.play(getApplication(), index)
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
}
