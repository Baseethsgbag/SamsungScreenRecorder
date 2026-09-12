package com.samsung.screenrecorder.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.samsung.screenrecorder.util.SamsungCompatibilityManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenRecorderScreen(
    isRecording: Boolean,
    isPaused: Boolean,
    onStartRecording: (resolution: String, fps: Int, audioSource: String) -> Unit,
    onPauseRecording: () -> Unit,
    onResumeRecording: () -> Unit,
    onStopRecording: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedResolution by remember { mutableStateOf("1080p") }
    var selectedFps by remember { mutableStateOf(60) }
    var selectedAudioSource by remember { mutableStateOf("both") }

    val oneUiVersion = remember { SamsungCompatibilityManager.oneUiVersion }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Screen Recorder", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Text(oneUiVersion, style = MaterialTheme.typography.bodySmall)
                    }
                },
                actions = {
                    IconButton(onClick = { /* Settings coming later */ }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(Icons.Default.VerifiedUser, contentDescription = null)
                    Column {
                        Text("Hardware-Aware Recording", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text(
                            "Adaptive recording profiles with audio capture and pause/resume controls.",
                            fontSize = 12.sp
                        )
                    }
                }
            }

            Card(
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        when {
                            isPaused -> "Recording Paused"
                            isRecording -> "Recording Active"
                            else -> "Ready to Record"
                        },
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Button(
                            onClick = {
                                if (isRecording) onStopRecording()
                                else onStartRecording(selectedResolution, selectedFps, selectedAudioSource)
                            },
                            shape = CircleShape,
                            modifier = Modifier.size(80.dp)
                        ) {
                            Icon(
                                if (isRecording) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                                contentDescription = if (isRecording) "Stop" else "Record",
                                modifier = Modifier.size(36.dp)
                            )
                        }

                        if (isRecording) {
                            FilledTonalButton(
                                onClick = if (isPaused) onResumeRecording else onPauseRecording,
                                modifier = Modifier.size(80.dp),
                                shape = CircleShape
                            ) {
                                Icon(
                                    if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                    contentDescription = if (isPaused) "Resume" else "Pause"
                                )
                            }
                        }
                    }
                }
            }

            Text("Audio Source", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text("Phase 1: microphone and mute are stable. Internal audio + mix comes in Phase 2.", fontSize = 12.sp)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    "internal" to "Media Only",
                    "mic" to "Mic Only",
                    "both" to "Media & Mic",
                    "mute" to "Mute"
                ).forEach { (id, label) ->
                    FilterChip(
                        selected = selectedAudioSource == id,
                        onClick = { selectedAudioSource = id },
                        label = { Text(label, fontSize = 12.sp) },
                        enabled = !isRecording
                    )
                }
            }

            Text("Video Quality", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("1440p", "1080p", "720p").forEach { res ->
                    FilterChip(
                        selected = selectedResolution == res,
                        onClick = { selectedResolution = res },
                        label = { Text(res) },
                        enabled = !isRecording
                    )
                }
            }

            Text("Frame Rate", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(30, 60).forEach { fps ->
                    FilterChip(
                        selected = selectedFps == fps,
                        onClick = { selectedFps = fps },
                        label = { Text("${fps} FPS") },
                        enabled = !isRecording
                    )
                }
            }

            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                ListItem(
                    leadingContent = { Icon(Icons.Default.Tune, contentDescription = null) },
                    headlineContent = { Text("Smart recording profile") },
                    supportingContent = {
                        Text("${selectedResolution} • ${selectedFps} FPS • ${selectedAudioSource.replaceFirstChar { it.uppercase() }}")
                    }
                )
            }
        }
    }
}
