package com.rutger.soundboard

import android.content.Context
import android.net.Uri
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.rutger.soundboard.update.Updater
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val KeyShape = RoundedCornerShape(14.dp)
private val PillShape = RoundedCornerShape(50)

@Composable
fun SoundboardScreen(viewModel: SoundboardViewModel) {
    val sounds by viewModel.sounds.collectAsState()
    val bleConnected by viewModel.bleConnected.collectAsState()
    val activeButton by viewModel.activeButton.collectAsState()
    val battery by viewModel.battery.collectAsState()

    var showBatteryStats by remember { mutableStateOf(false) }
    var editingSlot by remember { mutableStateOf<Int?>(null) }
    var browserSlot by remember { mutableStateOf<Int?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Header(
            connected = bleConnected,
            battery = battery,
            onBattery = { showBatteryStats = true },
        )
        UpdateCard()

        Column(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.Center,
        ) {
            Keypad(
                sounds = sounds,
                activeButton = activeButton,
                onPlay = viewModel::onButtonPressed,
                onEdit = { editingSlot = it },
            )
            Text(
                text = "Tap a key to play it, hold to change its sound",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            )
        }
    }

    editingSlot?.let { slot ->
        sounds.firstOrNull { it.id == slot }?.let { entry ->
            KeySheet(
                entry = entry,
                onDismiss = { editingSlot = null },
                onFileSelected = { uri -> viewModel.setAudioFile(slot, uri) },
                onBrowseMyInstants = { browserSlot = slot },
                onPlay = { viewModel.onButtonPressed(slot) },
                onClear = { viewModel.clearSlot(slot) },
            )
        }
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
private fun Header(connected: Boolean, battery: BatteryReading?, onBattery: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    ) {
        Text(
            text = "Soundboard",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        ConnectionPill(connected)
        battery?.let { BatteryPill(it, onClick = onBattery) }
        OverflowMenu()
    }
}

@Composable
private fun Pill(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .clip(PillShape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, PillShape)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        content = content,
    )
}

@Composable
private fun ConnectionPill(connected: Boolean) {
    val status = LocalStatusColors.current
    Pill {
        Box(
            Modifier
                .size(8.dp)
                .background(if (connected) status.ok else status.searching, CircleShape),
        )
        Text(
            text = if (connected) "Connected" else "Searching",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BatteryPill(reading: BatteryReading, onClick: () -> Unit) {
    val low = reading.percent < LOW_BATTERY_PERCENT
    Pill(modifier = Modifier.clickable(onClick = onClick)) {
        Text(
            text = if (low) "${reading.percent}% · charge" else "${reading.percent}%",
            style = MaterialTheme.typography.labelMedium,
            fontFamily = DataFont,
            color = if (low) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun OverflowMenu() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    val update by Updater.status.collectAsState()
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Outlined.MoreVert, contentDescription = "More")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Share logs") },
                leadingIcon = { Icon(Icons.Outlined.Share, contentDescription = null) },
                onClick = {
                    open = false
                    scope.launch { shareLogs(context) }
                },
            )
            DropdownMenuItem(
                text = {
                    Text(
                        versionLine(update),
                        fontFamily = DataFont,
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                leadingIcon = { Icon(Icons.Outlined.Refresh, contentDescription = null) },
                onClick = { Updater.checkNow() },
            )
        }
    }
}

private fun versionLine(status: Updater.Status): String {
    val newer = Updater.available(status)
    val outcome = when {
        newer != null -> "${newer.versionName} available"
        status is Updater.Status.Checking -> "checking…"
        status is Updater.Status.UpToDate -> "up to date"
        status is Updater.Status.Failed -> "check failed"
        else -> null
    }
    return listOfNotNull(BuildConfig.VERSION_NAME, outcome).joinToString(" · ")
}

/** Shown while a newer version exists; failures show the real error. */
@Composable
private fun UpdateCard() {
    val status by Updater.status.collectAsState()
    val release = Updater.available(status) ?: return
    val (text, action) = when (val s = status) {
        is Updater.Status.Downloading -> "Downloading ${release.versionName}: ${s.bytes * 100 / release.size.coerceAtLeast(1)}%" to null
        is Updater.Status.Installing -> "Installing ${release.versionName}…" to null
        is Updater.Status.Deferred -> "${release.versionName} installs once the soundboard is idle" to "Install"
        is Updater.Status.Failed -> "Update ${release.versionName} failed: ${s.message}" to "Retry"
        else -> "${release.versionName} available" to "Install"
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(KeyShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp)
            .heightIn(min = 36.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (status is Updater.Status.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (action != null) {
            TextButton(onClick = Updater::installNow) {
                Text(action)
            }
        }
    }
}

private suspend fun shareLogs(context: Context) {
    try {
        LogShare.share(context)
    } catch (e: Exception) {
        AppLog.e("LogShare", "Share failed", e)
        Toast.makeText(context, "Couldn't share logs: $e", Toast.LENGTH_LONG).show()
    }
}

@Composable
private fun Keypad(
    sounds: List<SoundEntry>,
    activeButton: Int?,
    onPlay: (Int) -> Unit,
    onEdit: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        sounds.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { entry ->
                    Key(
                        entry = entry,
                        lit = activeButton == entry.id,
                        onTap = { if (entry.uri != null) onPlay(entry.id) else onEdit(entry.id) },
                        onHold = { onEdit(entry.id) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Key(
    entry: SoundEntry,
    lit: Boolean,
    onTap: () -> Unit,
    onHold: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val empty = entry.uri == null
    val fill by animateColorAsState(
        targetValue = when {
            lit -> colors.primary
            empty -> Color.Transparent
            else -> colors.surfaceVariant
        },
        label = "key_fill",
    )
    val content = if (lit) colors.onPrimary else colors.onSurface
    val scale by animateFloatAsState(if (lit) 0.96f else 1f, label = "key_press")

    Column(
        modifier = modifier
            .aspectRatio(0.9f)
            .scale(scale)
            .clip(KeyShape)
            .background(fill)
            .border(1.dp, if (lit) colors.primary else colors.outlineVariant, KeyShape)
            .combinedClickable(onClick = onTap, onLongClick = onHold)
            .padding(10.dp),
    ) {
        Text(
            text = "${buttonLabel(entry.id)}",
            fontFamily = DataFont,
            fontSize = 13.sp,
            color = if (lit) colors.onPrimary else colors.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        if (empty) {
            Icon(
                Icons.Outlined.Add,
                contentDescription = "Add sound",
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        } else {
            Text(
                text = entry.displayName ?: "Sound",
                style = MaterialTheme.typography.labelLarge,
                lineHeight = 16.sp,
                color = content,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KeySheet(
    entry: SoundEntry,
    onDismiss: () -> Unit,
    onFileSelected: (Uri) -> Unit,
    onBrowseMyInstants: () -> Unit,
    onPlay: () -> Unit,
    onClear: () -> Unit,
) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(onFileSelected)
        onDismiss()
    }
    val hasAudio = entry.uri != null

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                Text(
                    "Key ${buttonLabel(entry.id)}",
                    fontFamily = DataFont,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    entry.displayName ?: "No sound yet",
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(8.dp))
            if (hasAudio) {
                SheetAction(Icons.Outlined.PlayArrow, "Play") { onPlay() }
            }
            SheetAction(Icons.Outlined.Search, "Find on MyInstants") {
                onBrowseMyInstants()
                onDismiss()
            }
            SheetAction(Icons.Outlined.Add, "Choose audio file") { launcher.launch(arrayOf("audio/*")) }
            if (hasAudio) {
                SheetAction(Icons.Outlined.Delete, "Remove sound", destructive = true) {
                    onClear()
                    onDismiss()
                }
            }
        }
    }
}

@Composable
private fun SheetAction(
    icon: ImageVector,
    label: String,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    ListItem(
        headlineContent = { Text(label, color = color) },
        leadingContent = { Icon(icon, contentDescription = null, tint = color) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 8.dp),
    )
}

/** Nerd stats about the soundboard's battery. */
@Composable
private fun BatteryStatsDialog(viewModel: SoundboardViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val reading by viewModel.battery.collectAsState()
    val session by viewModel.batterySession.collectAsState()
    val stats = remember(session) { computeBatteryStats(session) }

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
    val last = session.samples.lastOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = { TextButton(onClick = { scope.launch { shareLogs(context) } }) { Text("Share logs") } },
        title = { Text("Battery") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                StatLine("Voltage", reading?.millivolts?.let { "%.3f V".format(it / 1000.0) } ?: "—")
                StatLine("Percentage", reading?.let { "${it.percent}%" } ?: "—")
                StatLine("Charging", reading?.let { chargingLabel(it.charging) } ?: "—")
                StatLine("Capacity", "$BATTERY_CAPACITY_MAH mAh")
                StatLine("Used since charge", stats.mahUsed?.let { "${it.roundToInt()} mAh" } ?: noData)
                StatLine("Average draw", stats.averageMa?.let { "%.1f mA".format(it) } ?: noData)
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
            }
        },
    )
}

@Composable
private fun StatLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = DataFont, textAlign = TextAlign.End)
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

    LaunchedEffect(Unit) { viewModel.loadTrending() }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(8.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Key ${buttonLabel(slot)}",
                            fontFamily = DataFont,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text("MyInstants", style = MaterialTheme.typography.titleLarge)
                    }
                    TextButton(onClick = onDismiss) { Text("Close") }
                }

                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = query,
                    onValueChange = {
                        query = it
                        viewModel.searchMyInstants(it)
                    },
                    placeholder = { Text("Search sounds") },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    singleLine = true,
                    shape = PillShape,
                    modifier = Modifier.fillMaxWidth(),
                )

                Text(
                    text = if (query.isBlank()) "Trending" else "Results",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                )

                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        downloadingSlot == slot -> LoadingCenter("Downloading…")
                        loading -> LoadingCenter("Loading…")
                        results.isEmpty() -> Text(
                            "No sounds found. Try another search.",
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
                                                if (ok) "Added \"${sound.title}\" to key ${buttonLabel(slot)}"
                                                else "Download failed. Check your connection and try again.",
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                            if (ok) onDismiss()
                                        }
                                    },
                                )
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
        Text(
            text = sound.title.ifBlank { sound.id },
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(
            Icons.Outlined.Add,
            contentDescription = "Add to key",
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}
