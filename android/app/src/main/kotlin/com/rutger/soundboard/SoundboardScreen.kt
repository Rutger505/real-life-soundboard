package com.rutger.soundboard

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SoundboardScreen(viewModel: SoundboardViewModel) {
    val sounds by viewModel.sounds.collectAsState()
    val bleConnected by viewModel.bleConnected.collectAsState()
    val activeButton by viewModel.activeButton.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        // BLE status
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 12.dp),
        ) {
            val color = if (bleConnected) Color(0xFF4CAF50) else Color(0xFFF44336)
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(color, shape = RoundedCornerShape(6.dp)),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (bleConnected) "ESP32 Connected" else "Scanning for ESP32...",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(9) { index ->
                SoundButton(
                    entry = sounds[index],
                    isActive = activeButton == index,
                    onFileSelected = { uri -> viewModel.setAudioFile(index, uri) },
                    onPress = { viewModel.onButtonPressed(index) },
                )
            }
        }
    }
}

@Composable
fun SoundButton(
    entry: SoundEntry,
    isActive: Boolean,
    onFileSelected: (Uri) -> Unit,
    onPress: () -> Unit,
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

    Column(
        modifier = Modifier
            .aspectRatio(1f)
            .background(bgColor, shape = RoundedCornerShape(12.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, shape = RoundedCornerShape(12.dp))
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "${entry.id + 1}",
            fontSize = 20.sp,
            color = if (isActive) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(
            text = entry.displayName ?: "—",
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = if (isActive) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (entry.uri != null) {
                TextButton(
                    onClick = onPress,
                    contentPadding = PaddingValues(horizontal = 4.dp),
                ) {
                    Text("▶", fontSize = 12.sp)
                }
            }
            TextButton(
                onClick = { launcher.launch(arrayOf("audio/*")) },
                contentPadding = PaddingValues(horizontal = 4.dp),
            ) {
                Text("📂", fontSize = 12.sp)
            }
        }
    }
}
