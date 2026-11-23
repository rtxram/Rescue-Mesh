package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.WifiCalling3
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.domain.model.MeshConstants
import com.example.location.MapTileStyle
import com.example.security.QrPairingCodec
import com.example.ui.components.QrScannerDialog
import com.example.ui.components.TacticalCard
import com.example.ui.theme.LocalRescueColors
import com.example.ui.viewmodel.SettingsUiState

@Composable
fun ConfigScreen(
    uiState: SettingsUiState,
    onPairContactInput: (String) -> Unit,
    onDeleteContact: (String) -> Unit,
    onDownloadOfflineMap: (Double, MapTileStyle) -> Unit,
    onClearOfflineMap: () -> Unit,
    onTogglePreferInternetAudio: (Boolean) -> Unit,
    onSaveSignalingUrl: (String) -> Unit,
    onDismissBanner: () -> Unit,
    modifier: Modifier = Modifier
) {
    val rescue = LocalRescueColors.current
    val context = LocalContext.current

    var showQrScanner by remember { mutableStateOf(false) }
    var showManualDialog by remember { mutableStateOf(false) }
    var manualKeyInput by remember { mutableStateOf("") }
    var copiedFeedback by remember { mutableStateOf(false) }
    var selectedRadiusKm by remember { mutableDoubleStateOf(3.5) }
    var selectedMapStyle by remember(uiState.offlineMapStatus.style) {
        mutableStateOf(uiState.offlineMapStatus.style)
    }
    var signalingDraft by remember(uiState.settings.signalingServerUrl) {
        mutableStateOf(uiState.settings.signalingServerUrl)
    }

    val qrBitmap = remember(uiState.qrPairingUri) {
        if (uiState.qrPairingUri.isNotBlank()) {
            QrPairingCodec.generateQrBitmap(uiState.qrPairingUri, sizePx = 420)
        } else null
    }

    if (showQrScanner) {
        QrScannerDialog(
            onQrDecoded = { decoded ->
                showQrScanner = false
                onPairContactInput(decoded)
            },
            onDismiss = { showQrScanner = false }
        )
    }

    if (showManualDialog) {
        AlertDialog(
            onDismissRequest = { showManualDialog = false },
            title = { Text("Enter Pairing Key") },
            text = {
                Column {
                    Text(
                        text = "Paste an RMKEY2:... code or rescuemesh://pair URI shared by a trusted contact.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = manualKeyInput,
                        onValueChange = { manualKeyInput = it },
                        placeholder = { Text("RMKEY2:...") },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("manual_pairing_input")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onPairContactInput(manualKeyInput)
                        manualKeyInput = ""
                        showManualDialog = false
                    },
                    modifier = Modifier.testTag("confirm_manual_pair_button")
                ) {
                    Text("Pair Contact")
                }
            },
            dismissButton = {
                TextButton(onClick = { showManualDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("config_list"),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            AnimatedVisibility(visible = uiState.configBanner != null) {
                uiState.configBanner?.let { msg ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onDismissBanner),
                        shape = RoundedCornerShape(14.dp),
                        color = rescue.emeraldContainer
                    ) {
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.labelMedium,
                            color = rescue.emeraldActive,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }
        }

        // My Node QR Pairing Card
        item {
            TacticalCard(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "My Node Pairing QR",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = "${uiState.settings.deviceAlias} · #${uiState.localPeerId.take(4).uppercase()}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Surface(
                        color = rescue.emeraldContainer,
                        shape = CircleShape
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(Icons.Filled.Shield, contentDescription = null, tint = rescue.emeraldActive, modifier = Modifier.size(14.dp))
                            Text("X25519 + Ed25519", style = MaterialTheme.typography.labelSmall, color = rescue.emeraldActive)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                if (qrBitmap != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.White)
                            .padding(14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = "Your RescueMesh QR Pairing Code",
                            modifier = Modifier.size(188.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("RescueMesh Pairing Key", uiState.compactManualKey))
                        copiedFeedback = true
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("copy_manual_key_button")
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (copiedFeedback) "Copied RMKEY2 Key" else "Copy Manual Pairing Key")
                }
            }
        }

        // Trusted Paired Contacts (Max 7)
        item {
            TacticalCard(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Trusted Contacts (${uiState.contacts.size} / ${MeshConstants.MAX_PAIRED_CONTACTS})",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = "Only paired contacts decrypt your SOS. Intermediate nodes relay blindly.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                val atCap = uiState.contacts.size >= MeshConstants.MAX_PAIRED_CONTACTS
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        enabled = !atCap,
                        onClick = { showQrScanner = true },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("scan_contact_qr_button")
                    ) {
                        Icon(Icons.Filled.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Scan QR")
                    }

                    OutlinedButton(
                        enabled = !atCap,
                        onClick = { showManualDialog = true },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("manual_pair_button")
                    ) {
                        Icon(Icons.Filled.Key, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Enter Key")
                    }
                }
            }
        }

        items(uiState.contacts, key = { it.peerId }) { contact ->
            TacticalCard(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = contact.alias,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                        )
                        Text(
                            text = "NODE #${contact.peerId.take(8)} · X25519 E2EE Paired",
                            style = MaterialTheme.typography.labelSmall,
                            color = rescue.emeraldActive
                        )
                    }
                    IconButton(
                        onClick = { onDeleteContact(contact.peerId) },
                        modifier = Modifier.testTag("delete_contact_${contact.peerId}")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = "Remove paired contact",
                            tint = rescue.sosCrimson
                        )
                    }
                }
            }
        }

        // Local Google Maps Pre-Cacher
        item {
            TacticalCard(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Filled.Map, contentDescription = null, tint = MaterialTheme.colorScheme.primaryContainer)
                    Text(
                        text = "Local Google Maps (Offline Cache)",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = uiState.offlineMapStatus.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (uiState.offlineMapStatus.isDownloading && uiState.offlineMapStatus.totalTiles > 0) {
                    Spacer(modifier = Modifier.height(10.dp))
                    val progress = uiState.offlineMapStatus.downloadedTiles.toFloat() /
                        uiState.offlineMapStatus.totalTiles.toFloat()
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MapTileStyle.entries.forEach { style ->
                        FilterChip(
                            selected = selectedMapStyle == style,
                            onClick = { selectedMapStyle = style },
                            label = { Text(style.label) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(2.0 to "2 km", 3.5 to "3.5 km", 5.0 to "5 km").forEach { (rKm, label) ->
                        FilterChip(
                            selected = selectedRadiusKm == rKm,
                            onClick = { selectedRadiusKm = rKm },
                            label = { Text(label) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        enabled = !uiState.offlineMapStatus.isDownloading,
                        onClick = { onDownloadOfflineMap(selectedRadiusKm, selectedMapStyle) },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("download_osm_tiles_button")
                    ) {
                        Icon(Icons.Filled.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Download Area")
                    }
                    OutlinedButton(
                        onClick = onClearOfflineMap,
                        modifier = Modifier.height(48.dp)
                    ) {
                        Text("Clear")
                    }
                }
            }
        }

        // WebRTC Live Internet Voice Transport
        item {
            TacticalCard(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Filled.WifiCalling3, contentDescription = null, tint = MaterialTheme.colorScheme.primaryContainer)
                        Text(
                            text = "Voice Transport & WebRTC",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                    Surface(
                        color = if (uiState.isInternetReachable) rescue.emeraldContainer else MaterialTheme.colorScheme.surfaceContainer,
                        shape = CircleShape
                    ) {
                        Text(
                            text = if (uiState.isInternetReachable) "Internet Online" else "BLE Offline",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (uiState.isInternetReachable) rescue.emeraldActive else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                        Text("Live WebRTC Audio When Online", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Streams WebRTC audio when connected; falls back automatically to 5s BLE packets offline.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = uiState.settings.preferInternetAudioWhenOnline,
                        onCheckedChange = onTogglePreferInternetAudio
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = signalingDraft,
                        onValueChange = { signalingDraft = it },
                        label = { Text("WebRTC Signaling URL (wss://...)") },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("signaling_url_input")
                    )
                    Button(
                        onClick = { onSaveSignalingUrl(signalingDraft) },
                        modifier = Modifier.height(48.dp)
                    ) {
                        Text("Save")
                    }
                }
            }
        }
    }
}
