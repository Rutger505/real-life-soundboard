package com.rutger.soundboard

import kotlinx.coroutines.flow.MutableStateFlow

// ESP32 buttons 0..2 are broken, so only indices 3..8 are used. Indices stay
// as the ESP sends them so stored prefs ("uri_<index>") keep matching.
val BUTTON_IDS = 3..8

fun buttonLabel(id: Int): Int = id - BUTTON_IDS.first + 1

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
