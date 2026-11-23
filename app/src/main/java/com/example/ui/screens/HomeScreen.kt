package com.example.ui.screens

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.network.TransportStatus
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import com.example.ui.components.HoldToSosOrb
import com.example.ui.theme.LocalRescueColors
import com.example.ui.viewmodel.HomeUiState

@Composable
fun HomeScreen(
    uiState: HomeUiState,
    onTriggerSos: () -> Unit,
    onOpenActiveSession: (String) -> Unit,
    onOpenConfig: () -> Unit,
    onStartMesh: () -> Unit,
    onToggleSendToAll: (Boolean) -> Unit = {},
    onDismissBanner: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val rescue = LocalRescueColors.current
    val context = LocalContext.current

    val meshPermissions = remember {
        buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            add(Manifest.permission.RECORD_AUDIO)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        onStartMesh()
    }

    val isMeshReady = uiState.transportStatus == TransportStatus.ACTIVE_ADVERTISING_AND_SCANNING
    val shortNodeId = uiState.localPeerId.take(4).uppercase().ifEmpty { "MESH" }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        // Optional top banner if an outgoing SOS is already active
        uiState.activeOutgoingSession?.let { active ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .clickable { onOpenActiveSession(active.sessionId) }
                    .testTag("active_sos_banner"),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.72f),
                border = BorderStroke(1.dp, rescue.sosCrimson.copy(alpha = 0.28f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(9.dp)
                                .clip(CircleShape)
                                .background(rescue.sosCrimson)
                        )
                        Text(
                            text = if (active.sessionAccepted) {
                                "Responder Connected · Return to Session"
                            } else {
                                "SOS Beacon Spraying · ${active.deliveryStatus.label}"
                            },
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "Open active session",
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        uiState.homeBanner?.let { banner ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .testTag("home_banner"),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.85f),
                border = BorderStroke(1.dp, rescue.sosCrimson.copy(alpha = 0.35f))
            ) {
                Row(
                    modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = banner,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f)
                    )
                    if (uiState.contacts.isEmpty()) {
                        TextButton(
                            onClick = {
                                onDismissBanner()
                                onOpenConfig()
                            },
                            modifier = Modifier.testTag("home_banner_config_button")
                        ) {
                            Text("Config", fontWeight = FontWeight.Bold)
                        }
                    }
                    IconButton(
                        onClick = onDismissBanner,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("home_banner_dismiss_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Dismiss warning",
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        // Centered Prototype Hero (Node Badge + HoldToSosOrb + Broadcast Toggle + Radio helper)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // Subtle Node Badge matching sender_home_f506546c.html
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.78f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceContainerHigh),
                    modifier = Modifier.testTag("network_status_card")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(
                                    when (uiState.transportStatus) {
                                        TransportStatus.ACTIVE_ADVERTISING_AND_SCANNING -> rescue.emeraldActive
                                        TransportStatus.STARTING -> rescue.amberRelay
                                        else -> MaterialTheme.colorScheme.outline
                                    }
                                )
                        )
                        Text(
                            text = "NODE: #$shortNodeId",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (uiState.nearbyPeerCount > 0) {
                            Text(
                                text = "· ${uiState.nearbyPeerCount} NODES",
                                style = MaterialTheme.typography.labelSmall,
                                color = rescue.emeraldActive
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                HoldToSosOrb(
                    isActivating = uiState.isActivatingSos,
                    onSosTriggered = onTriggerSos
                )

                Spacer(modifier = Modifier.height(12.dp))

                // "Send to All" Broadcast vs Paired Contacts Toggle Card
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    border = BorderStroke(
                        1.dp,
                        if (uiState.sendToAllNodes) rescue.sosCrimson.copy(alpha = 0.35f)
                        else MaterialTheme.colorScheme.surfaceContainerHigh
                    ),
                    shadowElevation = 1.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggleSendToAll(!uiState.sendToAllNodes) }
                        .testTag("send_to_all_toggle_card")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (uiState.sendToAllNodes) rescue.sosCrimson.copy(alpha = 0.12f)
                                        else MaterialTheme.colorScheme.surfaceContainerHigh
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (uiState.sendToAllNodes) Icons.Filled.Sensors else Icons.Filled.Lock,
                                    contentDescription = null,
                                    tint = if (uiState.sendToAllNodes) rescue.sosCrimson else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = if (uiState.sendToAllNodes) "Send to All Nearby" else "Paired Only",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                Surface(
                                    shape = CircleShape,
                                    color = if (uiState.sendToAllNodes) rescue.sosCrimson.copy(alpha = 0.15f)
                                    else MaterialTheme.colorScheme.surfaceContainerHigh
                                ) {
                                    Text(
                                        text = if (uiState.sendToAllNodes) "BROADCAST" else "E2EE",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = if (uiState.sendToAllNodes) rescue.sosCrimson else MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Switch(
                            checked = uiState.sendToAllNodes,
                            onCheckedChange = onToggleSendToAll,
                            modifier = Modifier.testTag("send_to_all_switch")
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

            // Radio / Permission readiness helper chip when radio isn't running yet
            if (!isMeshReady) {
                val buttonLabel = when (uiState.transportStatus) {
                    TransportStatus.PERMISSION_MISSING -> "Grant Mesh Permissions"
                    TransportStatus.BLUETOOTH_DISABLED -> "Turn On Bluetooth"
                    TransportStatus.LOCATION_DISABLED -> "Turn On Location"
                    TransportStatus.HARDWARE_UNAVAILABLE -> "BLE Hardware Unavailable"
                    else -> "Start Mesh Radio"
                }
                FilledTonalButton(
                    onClick = {
                        val missing = meshPermissions.any { perm ->
                            ContextCompat.checkSelfPermission(context, perm) !=
                                PackageManager.PERMISSION_GRANTED
                        }
                        when {
                            missing -> permissionLauncher.launch(meshPermissions.toTypedArray())
                            uiState.transportStatus == TransportStatus.BLUETOOTH_DISABLED -> {
                                runCatching {
                                    context.startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                                }
                            }
                            uiState.transportStatus == TransportStatus.LOCATION_DISABLED -> {
                                runCatching {
                                    context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                                }
                            }
                            else -> onStartMesh()
                        }
                    },
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ),
                    modifier = Modifier.testTag("start_mesh_button")
                ) {
                    Icon(
                        imageVector = if (uiState.transportStatus == TransportStatus.BLUETOOTH_DISABLED) {
                            Icons.Filled.Bluetooth
                        } else {
                            Icons.Filled.Sensors
                        },
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(buttonLabel, style = MaterialTheme.typography.labelMedium)
                }
            } else if (uiState.contacts.isEmpty()) {
                Surface(
                    modifier = Modifier
                        .clickable { onOpenConfig() }
                        .testTag("pair_contacts_hint"),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceContainerHigh)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.QrCode2,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Pair up to 7 trusted contacts in Config",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
            }
        }
    }
}
