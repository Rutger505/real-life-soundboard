package com.rutger.soundboard

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
            items(9) { index ->
                SoundButton(
                    entry = sounds[index],
                    isActive = activeButton == index,
                    onFileSelected = { uri -> viewModel.setAudioFile(index, uri) },
                    onBrowseMyInstants = { browserSlot = index },
                    onPress = { viewModel.onButtonPressed(index) },
                    onClear = { viewModel.clearSlot(index) },
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Connection status sits just under the centered grid.
        BleStatusBar(connected = bleConnected)
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

    Column(
        modifier = Modifier
            // A fixed min height (instead of a strict square) guarantees room
            // for the number, name and BOTH action rows without clipping.
            .fillMaxWidth()
            .heightIn(min = 156.dp)
            .background(bgColor, shape = RoundedCornerShape(12.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, shape = RoundedCornerShape(12.dp))
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "${entry.id + 1}",
            fontSize = 20.sp,
            color = contentColor,
        )

        Text(
            text = entry.displayName ?: "—",
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = contentColor,
            modifier = Modifier.weight(1f),
        )

        // Source row: always available, so a slot can be re-sourced from a
        // local file or MyInstants even after one is set.
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SlotAction("📂") { launcher.launch(arrayOf("audio/*")) }
            SlotAction("🔍", onClick = onBrowseMyInstants)
        }

        // Playback row: ALWAYS shown so play/trash are visible on every tile.
        // When no sound is loaded the actions are disabled (greyed out).
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
                        text = "MyInstants → slot ${slot + 1}",
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
                                                if (ok) "Added \"${sound.title}\" to slot ${slot + 1}"
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
