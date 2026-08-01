package com.rutger.soundboard

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Process-wide shared state, owned by [SoundboardService] and observed by the UI.
 * Keeping it here decouples the Compose layer from the service lifecycle: the
 * service keeps running (BLE + audio) even when no Activity is bound.
 */
object SoundboardState {
    val bleConnected = MutableStateFlow(false)
    val activeButton = MutableStateFlow<Int?>(null)
    val lastButton = MutableStateFlow<Int?>(null)
}
