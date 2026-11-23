package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.domain.model.MeshConstants
import com.example.ui.components.TacticalCard
import com.example.ui.theme.LocalRescueColors
import com.example.ui.viewmodel.SettingsUiState
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    uiState: SettingsUiState,
    onUpdateAlias: (String) -> Unit,
    onToggleRelay: (Boolean) -> Unit,
    onUpdateMaxHop: (Int) -> Unit,
    onToggleShakeSos: (Boolean) -> Unit,
    onToggleVolumeShortcut: (Boolean) -> Unit,
    onToggleAutoRecordVoiceOnSos: (Boolean) -> Unit,
    onToggleAutoDownloadAudio: (Boolean) -> Unit,
    onToggleAutoPlaySpeakerOnSession: (Boolean) -> Unit,
    onToggleSendToAll: (Boolean) -> Unit = {},
    onPermissionsGranted: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val rescue = LocalRescueColors.current
    val context = LocalContext.current
    var permissionRefreshTrigger by remember { mutableIntStateOf(0) }

    val requiredPermissions = remember {
        buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    val permLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        permissionRefreshTrigger++
        onPermissionsGranted()
    }

    var aliasInput by remember(uiState.settings.deviceAlias) { mutableStateOf(uiState.settings.deviceAlias) }

    val inputColors = TextFieldDefaults.colors(
        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        focusedIndicatorColor = Color.Transparent,
        unfocusedIndicatorColor = Color.Transparent
    )

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Column {
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold)
                )
                Text(
                    text = "NODE #${uiState.localPeerId.take(8)} · Ed25519 + X25519 Mesh",
                    style = MaterialTheme.typography.labelSmall,
                    color = rescue.emeraldActive
                )
            }
        }

        // Mesh Identity & Relay
        item {
            TacticalCard(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
                Text(
                    text = "Mesh & Relay",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primaryContainer
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextField(
                        value = aliasInput,
                        onValueChange = { aliasInput = it },
                        placeholder = { Text("Node Alias") },
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium,
                        colors = inputColors,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("alias_input")
                    )
                    FilledTonalButton(
                        onClick = { onUpdateAlias(aliasInput) },
                        modifier = Modifier.testTag("save_alias_button")
                    ) {
                        Text("Save")
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))
                SettingSwitchRow(
                    title = "Blind Store-Carry-Forward Relay",
                    subtitle = "Store and forward encrypted SOS packets without decrypting them",
                    checked = uiState.settings.relayParticipationEnabled,
                    onCheckedChange = onToggleRelay,
                    testTag = "relay_toggle"
                )
                Spacer(modifier = Modifier.height(14.dp))
                SettingSwitchRow(
                    title = "Unlimited Hop Forwarding",
                    subtitle = "Allow SOS messages to traverse arbitrary intermediate hops with 12h TTL deduplication",
                    checked = uiState.settings.maxHopCount >= MeshConstants.UNLIMITED_HOPS,
                    onCheckedChange = { unlimited ->
                        onUpdateMaxHop(if (unlimited) MeshConstants.UNLIMITED_HOPS else 10)
                    },
                    testTag = "unlimited_hops_toggle"
                )
                Spacer(modifier = Modifier.height(14.dp))
                SettingSwitchRow(
                    title = "Send to All Nearby Nodes",
                    subtitle = "Broadcast SOS distress to all nodes in range instead of restricting to paired contacts",
                    checked = uiState.settings.sendToAllNodes,
                    onCheckedChange = onToggleSendToAll,
                    testTag = "send_to_all_settings_toggle"
                )

                if (uiState.settings.maxHopCount < MeshConstants.UNLIMITED_HOPS) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Finite Hop Cap: ${uiState.settings.maxHopCount} hops",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Slider(
                        value = uiState.settings.maxHopCount.coerceAtMost(20).toFloat(),
                        onValueChange = { onUpdateMaxHop(it.roundToInt()) },
                        valueRange = MeshConstants.MIN_HOP_COUNT.toFloat()..20f,
                        steps = 18,
                        modifier = Modifier.testTag("max_hop_slider")
                    )
                }
            }
        }

        // Permissions
        item {
            val trigger = permissionRefreshTrigger
            val grantedCount = requiredPermissions.count {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
            TacticalCard(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Permissions",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primaryContainer
                    )
                    Text(
                        text = "$grantedCount/${requiredPermissions.size} granted",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (grantedCount == requiredPermissions.size) rescue.emeraldActive else rescue.amberRelay
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                requiredPermissions.forEach { perm ->
                    val granted = ContextCompat.checkSelfPermission(context, perm) ==
                        PackageManager.PERMISSION_GRANTED
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = perm.substringAfterLast('.'),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = if (granted) "Granted" else "Needed",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (granted) rescue.emeraldActive else rescue.amberRelay
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                FilledTonalButton(
                    onClick = { permLauncher.launch(requiredPermissions.toTypedArray()) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("request_permissions_button")
                ) {
                    Text(if (trigger >= 0) "Grant Required Permissions" else "Grant Permissions")
                }
            }
        }

        // SOS Activation & Live Audio
        item {
            TacticalCard(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
                Text(
                    text = "SOS & Continuous Voice",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = rescue.sosCrimson
                )
                Spacer(modifier = Modifier.height(10.dp))
                SettingSwitchRow(
                    title = "Auto-Stream Live Audio on SOS",
                    subtitle = "Starts WebRTC online or continuous 5s BLE voice packets when SOS triggers",
                    checked = uiState.settings.autoRecordVoiceOnSos,
                    onCheckedChange = onToggleAutoRecordVoiceOnSos,
                    testTag = "auto_record_sos_toggle"
                )
                Spacer(modifier = Modifier.height(10.dp))
                SettingSwitchRow(
                    title = "Auto-Receive Mesh Voice Clips",
                    subtitle = "Reassemble incoming Priority-5 BLE audio segments",
                    checked = uiState.settings.autoDownloadAudioClips,
                    onCheckedChange = onToggleAutoDownloadAudio,
                    testTag = "auto_audio_toggle"
                )
                Spacer(modifier = Modifier.height(10.dp))
                SettingSwitchRow(
                    title = "Auto-Play Speaker in Active Session",
                    subtitle = "Automatically play incoming 5s voice clips sequentially on Listen",
                    checked = uiState.settings.autoPlaySpeakerOnSession,
                    onCheckedChange = onToggleAutoPlaySpeakerOnSession,
                    testTag = "auto_speaker_toggle"
                )
                Spacer(modifier = Modifier.height(10.dp))
                SettingSwitchRow(
                    title = "Shake-to-Arm SOS",
                    subtitle = "3 rapid shakes open an SOS confirmation dialog in-app",
                    checked = uiState.settings.shakeSosEnabled,
                    onCheckedChange = onToggleShakeSos,
                    testTag = "shake_sos_toggle"
                )
                Spacer(modifier = Modifier.height(10.dp))
                SettingSwitchRow(
                    title = "Volume-Down Triple-Press",
                    subtitle = "Arms SOS confirmation while app is open",
                    checked = uiState.settings.volumeShortcutEnabled,
                    onCheckedChange = onToggleVolumeShortcut,
                    testTag = "volume_shortcut_toggle"
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Wi-Fi Aware: ${if (uiState.wifiAwareSupported) "Hardware supported" else "Unavailable on device"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag)
        )
    }
}
