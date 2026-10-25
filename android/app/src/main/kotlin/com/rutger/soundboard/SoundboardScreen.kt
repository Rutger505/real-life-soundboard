package com.rutger.soundboard

import android.net.Uri
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
fun SoundboardScreen(viewModel: SoundboardViewModel) {
    val sounds by viewModel.sounds.collectAsState()
    val bleConnected by viewModel.bleConnected.collectAsState()
    val activeButton by viewModel.activeButton.collectAsState()
    val battery by viewModel.battery.collectAsState()

    var showBatteryStats by remember { mutableStateOf(false) }

    // Which slot (if any) is picking a sound from MyInstants.
    var browserSlot by remember { mutableStateOf<Int?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            // Keep all content inside the safe area, so nothing lands under the
            // camera cutout, status bar or nav bar.
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(16.dp),
        // Center the grid vertically in the available (safe) space.
        verticalArrangement = Arrangement.Center,
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            gridItems(sounds, key = { it.id }) { entry ->
                val index = entry.id
                SoundButton(
                    entry = entry,
                    isActive = activeButton == index,
                    onFileSelected = { uri -> viewModel.setAudioFile(index, uri) },
                    onBrowseMyInstants = { browserSlot = index },
                    onPress = { viewModel.onButtonPressed(index) },
                    onClear = { viewModel.clearSlot(index) },
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Hidden on firmware without battery characteristics.
        battery?.let { BatteryRow(it, onInfo = { showBatteryStats = true }) }

        // Connection status sits just under the centered grid.
        BleStatusBar(connected = bleConnected)
    }

    if (showBatteryStats) {
        BatteryStatsDialog(viewModel = viewModel, onDismiss = { showBatteryStats = false })
    }

    browserSlot?.let { slot ->
        MyInstantsBrowser(
            slot = slot,
            viewModel = viewModel,
            onDismiss = {
                browserSlot = null
                viewModel.clearMyInstantsResults()
            },
        )
    }
}

@Composable
private fun BatteryRow(reading: BatteryReading, onInfo: () -> Unit) {
    val low = reading.percent < LOW_BATTERY_PERCENT
    val color = if (low) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = buildString {
                append("Battery ${reading.percent}%")
                reading.millivolts?.let { append(" · %.2f V".format(it / 1000.0)) }
                if (low) append(" · low, charge soon")
            },
            style = MaterialTheme.typography.bodyMedium,
            color = color,
        )
        Spacer(modifier = Modifier.weight(1f))
        IconButton(onClick = onInfo) {
            Icon(Icons.Outlined.Info, contentDescription = "Battery stats")
        }
    }
}

/** Nerd stats about the soundboard's battery, plus the capacity setting. */
@Composable
private fun BatteryStatsDialog(viewModel: SoundboardViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val reading by viewModel.battery.collectAsState()
    val session by viewModel.batterySession.collectAsState()
    val capacity by viewModel.batteryCapacity.collectAsState()
    val stats = remember(session, capacity) { computeBatteryStats(session, capacity) }
    var capacityText by remember(capacity) { mutableStateOf(capacity?.toString() ?: "") }

    // Durations relative to now, so tick while the dialog is open.
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis()
        }
    }

    fun time(ms: Long) = DateUtils.formatDateTime(
        context, ms,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_ALL,
    )
    val noData = "not enough data"
    val noCapacity = "set capacity"
    val last = session.samples.lastOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Battery") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                StatLine("Voltage", reading?.millivolts?.let { "%.3f V".format(it / 1000.0) } ?: "—")
                StatLine("Percentage", reading?.let { "${it.percent}%" } ?: "—")
                StatLine("Charging", reading?.let { chargingLabel(it.charging) } ?: "—")
                StatLine("Capacity", capacity?.let { "$it mAh" } ?: "not set")
                StatLine(
                    "Used since charge",
                    if (capacity == null) noCapacity
                    else stats.mahUsed?.let { "${it.roundToInt()} mAh" } ?: noData,
                )
                StatLine(
                    "Average draw",
                    if (capacity == null) noCapacity
                    else stats.averageMa?.let { "%.1f mA".format(it) } ?: noData,
                )
                StatLine("Drain", stats.drainPercentPerHour?.let { "%.2f %%/h".format(it) } ?: noData)
                StatLine(
                    "Time remaining",
                    stats.emptyAtMs?.let { formatDuration((it - now).coerceAtLeast(0)) } ?: noData,
                )
                StatLine("Empty at", stats.emptyAtMs?.let { time(it) } ?: noData)
                StatLine("Since charge", session.startMs?.let { formatDuration(now - it) } ?: "—")
                StatLine("Samples", "${session.samples.size}")
                StatLine("Last measurement", last?.let { time(it.timeMs) } ?: "—")
                StatLine("Presses since charge", "${session.presses}")

                Spacer(Modifier.height(8.dp))
                Text(
                    "A charge is detected when the level jumps up 10 points or more. " +
                        "Readings are ±5-10 %, so the estimate needs an hour and a few % of data.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = capacityText,
                        onValueChange = { text -> capacityText = text.filter { it.isDigit() }.take(6) },
                        label = { Text("Capacity (mAh)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = { viewModel.setBatteryCapacity(capacityText.toIntOrNull()) },
                        enabled = capacityText != (capacity?.toString() ?: ""),
                    ) { Text("Save") }
                }
            }
        },
    )
}

@Composable
private fun StatLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** "2 d 4 h", "3 h 20 min", "12 min". */
private fun formatDuration(ms: Long): String {
    val minutes = ms / 60_000
    val hours = minutes / 60
    val days = hours / 24
    return when {
        days > 0 -> "$days d ${hours % 24} h"
        hours > 0 -> "$hours h ${minutes % 60} min"
        else -> "$minutes min"
    }
}

@Composable
private fun BleStatusBar(connected: Boolean) {
    val color = if (connected) Color(0xFF4CAF50) else Color(0xFFF44336)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(color, shape = RoundedCornerShape(5.dp)),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = if (connected) "ESP32 connected" else "Scanning for ESP32…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = "v${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun SoundButton(
    entry: SoundEntry,
    isActive: Boolean,
    onFileSelected: (Uri) -> Unit,
    onBrowseMyInstants: () -> Unit,
    onPress: () -> Unit,
    onClear: () -> Unit,
) {
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri -> uri?.let { onFileSelected(it) } },
    )

    val bgColor by animateColorAsState(
        targetValue = if (isActive) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceVariant,
        label = "button_color",
    )

    val hasAudio = entry.uri != null
    val contentColor = if (isActive) MaterialTheme.colorScheme.onPrimary
    else MaterialTheme.colorScheme.onSurfaceVariant

    // Plain vertical flow: every child takes its own natural size, so the
    // number, the (possibly multi-line) sound name and both button rows are
    // always fully visible and the tile grows to fit its content.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor, shape = RoundedCornerShape(12.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, shape = RoundedCornerShape(12.dp))
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "${buttonLabel(entry.id)}",
            fontSize = 20.sp,
            color = contentColor,
        )

        Text(
            text = entry.displayName ?: "—",
            style = MaterialTheme.typography.bodySmall,
            color = contentColor,
        )

        // Source row.
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            SlotAction("📂") { launcher.launch(arrayOf("audio/*")) }
            SlotAction("🔍", onClick = onBrowseMyInstants)
        }

        // Playback row: always shown, disabled when the slot is empty.
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            SlotAction("▶", enabled = hasAudio, onClick = onPress)
            SlotAction("🗑", enabled = hasAudio, onClick = onClear)
        }
    }
}

@Composable
private fun SlotAction(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 4.dp),
    ) {
        Text(label, fontSize = 12.sp)
    }
}

/**
 * Full-screen MyInstants browser: search box + result list. Tapping a result
 * downloads its mp3 and assigns it to [slot], then closes.
 */
@Composable
fun MyInstantsBrowser(
    slot: Int,
    viewModel: SoundboardViewModel,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val results by viewModel.miResults.collectAsState()
    val loading by viewModel.miLoading.collectAsState()
    val downloadingSlot by viewModel.miDownloadingSlot.collectAsState()
    var query by remember { mutableStateOf("") }

    // Load trending as soon as the browser opens.
    LaunchedEffect(Unit) { viewModel.loadTrending() }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 4.dp,
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "MyInstants → slot ${buttonLabel(slot)}",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDismiss) { Text("Close") }
                }

                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = query,
                    onValueChange = {
                        query = it
                        viewModel.searchMyInstants(it)
                    },
                    label = { Text("Search sounds") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(8.dp))

                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        downloadingSlot == slot -> LoadingCenter("Downloading…")
                        loading -> LoadingCenter("Loading…")
                        results.isEmpty() -> Text(
                            "No results",
                            modifier = Modifier.align(Alignment.Center),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        else -> LazyColumn(Modifier.fillMaxSize()) {
                            items(results) { sound ->
                                SoundResultRow(
                                    sound = sound,
                                    onClick = {
                                        viewModel.assignFromMyInstants(slot, sound) { ok ->
                                            Toast.makeText(
                                                context,
                                                if (ok) "Added \"${sound.title}\" to slot ${buttonLabel(slot)}"
                                                else "Download failed",
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                            if (ok) onDismiss()
                                        }
                                    },
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadingCenter(text: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(8.dp))
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SoundResultRow(sound: MyInstantSound, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
    ) {
        Text("🔊", fontSize = 18.sp)
        Spacer(Modifier.width(12.dp))
        Text(
            text = sound.title.ifBlank { sound.id },
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text("＋", fontSize = 20.sp, color = MaterialTheme.colorScheme.primary)
    }
}
