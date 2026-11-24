package com.rutger.soundboard

import kotlinx.coroutines.flow.MutableStateFlow

val BUTTON_IDS = 0..8

fun buttonLabel(id: Int): Int = id + 1

/**
 * Process-wide shared state, owned by [SoundboardService] and observed by the UI.
 * Keeping it here decouples the Compose layer from the service lifecycle: the
 * service keeps running (BLE + audio) even when no Activity is bound.
 */
object SoundboardState {
    val bleConnected = MutableStateFlow(false)
    val activeButton = MutableStateFlow<Int?>(null)
    val lastButton = MutableStateFlow<Int?>(null)

    /** elapsedRealtime of the last key press, 0 = none: the updater doesn't install mid-use. */
    val lastPressAt = MutableStateFlow(0L)

    /** Latest battery values; null while disconnected or on firmware without battery support. */
    val battery = MutableStateFlow<BatteryReading?>(null)

    /** Samples and presses since the last charge, persisted by [BatteryHistory]. */
    val batterySession = MutableStateFlow(BatterySession())
}
