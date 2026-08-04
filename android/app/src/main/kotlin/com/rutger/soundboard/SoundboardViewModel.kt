package com.rutger.soundboard

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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

    // ---- MyInstants browser state ----
    private val _miResults = MutableStateFlow<List<MyInstantSound>>(emptyList())
    val miResults: StateFlow<List<MyInstantSound>> = _miResults.asStateFlow()

    private val _miLoading = MutableStateFlow(false)
    val miLoading: StateFlow<Boolean> = _miLoading.asStateFlow()

    /** Slot index currently being downloaded into, or null. */
    private val _miDownloadingSlot = MutableStateFlow<Int?>(null)
    val miDownloadingSlot: StateFlow<Int?> = _miDownloadingSlot.asStateFlow()

    private var searchJob: Job? = null

    /** Load trending sounds (used when the browser opens with an empty query). */
    fun loadTrending() {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _miLoading.value = true
            _miResults.value = MyInstants.trending()
            _miLoading.value = false
        }
    }

    /** Search MyInstants; blank query falls back to trending. */
    fun searchMyInstants(query: String) {
        val q = query.trim()
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _miLoading.value = true
            _miResults.value =
                if (q.isEmpty()) MyInstants.trending() else MyInstants.search(q)
            _miLoading.value = false
        }
    }

    fun clearMyInstantsResults() {
        searchJob?.cancel()
        _miResults.value = emptyList()
        _miLoading.value = false
    }

    /**
     * Download [sound] and assign it to [slot]. [onDone] fires on the main
     * thread with success/failure so the UI can react (close dialog / toast).
     */
    fun assignFromMyInstants(slot: Int, sound: MyInstantSound, onDone: (Boolean) -> Unit) {
        if (slot !in 0..8) { onDone(false); return }
        viewModelScope.launch {
            _miDownloadingSlot.value = slot
            val context = getApplication<Application>()
            val uri = MyInstants.download(context, sound)
            if (uri != null) {
                storeSlot(slot, uri, sound.title)
                SoundboardService.reload(context)
            }
            _miDownloadingSlot.value = null
            onDone(uri != null)
        }
    }

    fun setAudioFile(id: Int, uri: Uri) {
        val context = getApplication<Application>()
        context.contentResolver.takePersistableUriPermission(
            uri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
        val name = queryDisplayName(uri)
        storeSlot(id, uri, name)
        // Have the service preload the new file so playback stays instant.
        SoundboardService.reload(context)
    }

    /** Persist a slot's uri/name to prefs and update the observable list. */
    private fun storeSlot(id: Int, uri: Uri, name: String?) {
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
