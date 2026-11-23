package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.domain.model.NoiseHandshakeState
import com.example.domain.model.Peer
import com.example.domain.model.PeerConnectionState
import com.example.network.TransportStatus
import com.example.ui.components.TacticalCard
import com.example.ui.theme.LocalRescueColors
import com.example.ui.viewmodel.PeersUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Nodes tab UI matching code_bf876aad.html:
 * - Discovered Peers header with active count pill and Rescan button (animated refresh icon)
 * - My Node Telemetry Summary Card ("X Active Peers Discovered", "All tracked nodes visible on proximity radar", "Synced")
 * - Tactical Radar / Mesh Proximity Field Widget:
 *     - 3.0 km range chip
 *     - Concentric dashed circles (1/4, 1/2, 3/4, 1.0) + crosshairs + animated sweep beam
 *     - Center: Local node indicator "YOU (#XXXX)" with my_location icon
 *     - Dynamic peer pins plotted with distance & bearing, animated ping ring, and label badge
 *     - Sub-legend: "Heading: 018° NNE", "Outer Ring: 3000m"
 * - Discovered Peer Nodes section with signal meters, relay status, Noise verification
 * - Mesh Routing Activity diagnostics log
 */
@Composable
fun NearbyPeersScreen(
    uiState: PeersUiState,
    onFlushQueue: () -> Unit,
    onRescan: () -> Unit,
    onVerifyNoise: (Peer) -> Unit,
    modifier: Modifier = Modifier
) {
    val rescue = LocalRescueColors.current
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val shortLocalId = uiState.localPeerId.take(4).uppercase().ifEmpty { "7429" }

    val activeCount = uiState.peers.count {
        it.connectionState == PeerConnectionState.CONNECTED ||
            (System.currentTimeMillis() - it.lastSeenAtMillis) < 45_000L
    }

    // Refresh icon spin animation
    val infiniteTransition = rememberInfiniteTransition(label = "peers_rescan_spin")
    val rescanRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rescan_spin"
    )

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Top Header: "Discovered Peers", "X active" pill, and "Rescan" button
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Discovered Peers",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier.testTag("active_peers_badge")
                    ) {
                        Text(
                            text = "$activeCount active",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerLowest,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceContainerHigh),
                        shadowElevation = 1.dp,
                        modifier = Modifier
                            .clickable { onRescan() }
                            .testTag("rescan_peers_button")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = "Rescan",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .size(15.dp)
                                    .rotate(if (uiState.isRescanning) rescanRotation else 0f)
                            )
                            Text(
                                text = "Rescan",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    FilledTonalButton(
                        onClick = onFlushQueue,
                        modifier = Modifier.testTag("flush_queue_button"),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        )
                    ) {
                        Icon(Icons.Filled.Sync, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Flush", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        // My Node Telemetry Summary Card
        item {
            TacticalCard(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Hub,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "$activeCount Active Peers Discovered",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "All tracked nodes visible on proximity radar",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh
                    ) {
                        Text(
                            text = if (activeCount > 0) "Synced" else "Scanning",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }

        // Interactive Tactical Radar / Visual Map Widget ("Mesh Proximity Field")
        item {
            TacticalRadarCard(
                uiState = uiState,
                shortLocalId = shortLocalId
            )
        }

        // Handshake / Diagnostic notification banner if present
        item {
            AnimatedVisibility(visible = uiState.handshakeBanner != null) {
                uiState.handshakeBanner?.let { msg ->
                    Surface(
                        color = rescue.emeraldContainer,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth()
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

        // Tracked Nodes Section Header
        item {
            Text(
                text = "Tracked Nodes (${uiState.peers.size})",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        // Peer cards list or empty state
        if (uiState.peers.isEmpty()) {
            val emptyTitle = when (uiState.transportStatus) {
                TransportStatus.PERMISSION_MISSING -> "Bluetooth & Location permissions needed"
                TransportStatus.BLUETOOTH_DISABLED -> "Bluetooth is turned off"
                TransportStatus.LOCATION_DISABLED -> "Device Location is turned off"
                TransportStatus.HARDWARE_UNAVAILABLE -> "BLE radio unavailable"
                TransportStatus.STOPPED -> "Mesh discovery is paused"
                else -> "Scanning for RescueMesh nodes..."
            }
            val emptySubtitle = when (uiState.transportStatus) {
                TransportStatus.PERMISSION_MISSING -> "Grant Nearby Devices / Bluetooth permissions to discover mesh peers."
                TransportStatus.BLUETOOTH_DISABLED -> "Enable Bluetooth on both devices to start mesh discovery."
                TransportStatus.LOCATION_DISABLED -> "Android 11 and older require system Location ON for BLE scanning."
                TransportStatus.STOPPED -> "Return to Home or tap Rescan to start discovery."
                else -> "Any nearby RescueMesh device in range will be displayed on the tactical proximity radar."
            }
            item {
                TacticalCard(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Filled.BluetoothSearching,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = emptyTitle,
                                style = MaterialTheme.typography.titleMedium,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = emptySubtitle,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        } else {
            items(uiState.peers, key = { it.peerId }) { peer ->
                val stateColor = when (peer.connectionState) {
                    PeerConnectionState.CONNECTED -> rescue.emeraldActive
                    PeerConnectionState.CONNECTING -> rescue.amberRelay
                    PeerConnectionState.DISCOVERED -> MaterialTheme.colorScheme.primaryContainer
                    PeerConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                val estMeters = estimateDistanceMeters(peer.rssi)
                val distText = formatDistance(estMeters)

                TacticalCard(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                    borderColor = stateColor.copy(alpha = 0.45f),
                    modifier = Modifier.testTag("peer_card_${peer.peerId}")
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = peer.displayName,
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                )
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    shape = CircleShape
                                ) {
                                    Text(
                                        text = "~$distText",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Text(
                                text = "NODE #${peer.peerId.take(4).uppercase()} · ${peer.bluetoothAddress ?: "BLE"}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Surface(
                            color = stateColor.copy(alpha = 0.14f),
                            shape = CircleShape
                        ) {
                            Text(
                                text = peer.connectionState.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = stateColor,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // 4-bar signal strength meter
                            Row(
                                verticalAlignment = Alignment.Bottom,
                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                (1..4).forEach { bar ->
                                    val filled = bar <= peer.signalBars
                                    Box(
                                        modifier = Modifier
                                            .width(4.dp)
                                            .height((6 + bar * 3).dp)
                                            .clip(RoundedCornerShape(2.dp))
                                            .background(
                                                if (filled) rescue.emeraldActive
                                                else MaterialTheme.colorScheme.surfaceContainerHigh
                                            )
                                    )
                                }
                            }
                            Text(
                                text = "${peer.rssi} dBm (${peer.signalBand})",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        Text(
                            text = if (peer.isRelayEnabled) "Blind Relay Active" else "Relay Off",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (peer.isRelayEnabled) rescue.amberRelay else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.Lock,
                                contentDescription = null,
                                tint = if (peer.noiseState == NoiseHandshakeState.ESTABLISHED) rescue.emeraldActive
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = peer.noiseState.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (peer.noiseState == NoiseHandshakeState.ESTABLISHED) rescue.emeraldActive
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        OutlinedButton(
                            onClick = { onVerifyNoise(peer) },
                            modifier = Modifier.testTag("noise_verify_${peer.peerId}")
                        ) {
                            Text("Verify Noise", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }

        // Priority Scheduler & Mesh Activity Log
        item {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Mesh Routing Activity (${uiState.diagnostics.size})",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (uiState.diagnostics.isEmpty()) {
            item {
                TacticalCard(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
                    Text(
                        text = "No packet routing events logged yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            items(uiState.diagnostics, key = { it.id }) { log ->
                TacticalCard(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "${log.priority.label} · ${log.packetType.name}",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.primaryContainer
                        )
                        Text(
                            text = timeFmt.format(Date(log.timestampMillis)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = log.detail,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${log.packetId.take(8)} · ${log.attempts} attempts · ${log.deliveryStatus.label}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * Tactical Radar / Mesh Proximity Field Widget matching code_bf876aad.html
 */
@Composable
private fun TacticalRadarCard(
    uiState: PeersUiState,
    shortLocalId: String
) {
    val rescue = LocalRescueColors.current
    val surfaceContainer = MaterialTheme.colorScheme.surfaceContainer
    val outlineVariant = MaterialTheme.colorScheme.outlineVariant
    val primaryContainer = MaterialTheme.colorScheme.primaryContainer

    // Rotating radar sweep beam animation (0..360 degrees)
    val sweepTransition = rememberInfiniteTransition(label = "radar_sweep_anim")
    val sweepAngle by sweepTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sweep_angle"
    )

    // Pulsing ping ring animation for blips
    val pingAnim by sweepTransition.animateFloat(
        initialValue = 1f,
        targetValue = 2.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ping_anim"
    )
    val pingAlpha by sweepTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ping_alpha"
    )

    TacticalCard(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Widget Title & Range Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Radar,
                        contentDescription = "Radar",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "Mesh Proximity Field",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Text(
                        text = "Range: 3.0 km",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            // Circular Radar Canvas Box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth(0.92f)
                        .aspectRatio(1f),
                    contentAlignment = Alignment.Center
                ) {
                    val boxWidth = maxWidth
                    val boxHeight = maxHeight

                    // Radar canvas: Concentric dashed rings + radial crosshairs + sweep line
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val cX = size.width / 2f
                        val cY = size.height / 2f
                        val maxR = size.minDimension / 2f * 0.94f

                        val dashEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)

                        // 1/4 filled center disc
                        drawCircle(
                            color = surfaceContainer.copy(alpha = 0.45f),
                            radius = maxR * 0.25f,
                            center = Offset(cX, cY)
                        )

                        // Concentric dashed rings: 1/4, 1/2, 3/4, 1.0 (Outer 3000m ring)
                        listOf(0.25f, 0.50f, 0.75f, 1.0f).forEach { fraction ->
                            drawCircle(
                                color = outlineVariant.copy(alpha = 0.35f + (fraction * 0.15f)),
                                radius = maxR * fraction,
                                center = Offset(cX, cY),
                                style = Stroke(
                                    width = 1.2.dp.toPx(),
                                    pathEffect = dashEffect
                                )
                            )
                        }

                        // Horizontal crosshair line
                        drawLine(
                            color = outlineVariant.copy(alpha = 0.25f),
                            start = Offset(0f, cY),
                            end = Offset(size.width, cY),
                            strokeWidth = 1.dp.toPx()
                        )

                        // Vertical crosshair line
                        drawLine(
                            color = outlineVariant.copy(alpha = 0.25f),
                            start = Offset(cX, 0f),
                            end = Offset(cX, size.height),
                            strokeWidth = 1.dp.toPx()
                        )

                        // Rotating subtle tactical sweep line
                        val sweepRad = Math.toRadians(sweepAngle.toDouble())
                        val sweepEndX = cX + (maxR * cos(sweepRad)).toFloat()
                        val sweepEndY = cY + (maxR * sin(sweepRad)).toFloat()
                        drawLine(
                            color = primaryContainer.copy(alpha = 0.35f),
                            start = Offset(cX, cY),
                            end = Offset(sweepEndX, sweepEndY),
                            strokeWidth = 1.5.dp.toPx()
                        )
                    }

                    // Local Node Indicator (Center "YOU (#XXXX)")
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.align(Alignment.Center)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.onSurface),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.MyLocation,
                                contentDescription = "Local node",
                                tint = MaterialTheme.colorScheme.surface,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(top = 2.dp)
                        ) {
                            Text(
                                text = "YOU (#$shortLocalId)",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.surface,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    // Display Peer blips on the radar
                    val peersToDisplay = if (uiState.peers.isNotEmpty()) {
                        uiState.peers.take(6)
                    } else {
                        // If no peers yet discovered, show subtle radar calibration blip
                        emptyList()
                    }

                    peersToDisplay.forEachIndexed { index, peer ->
                        val estDist = estimateDistanceMeters(peer.rssi)
                        val distText = formatDistance(estDist)
                        val angleRad = computePeerAngle(peer.peerId, index)

                        // Map 0..3000m to radius fraction between 0.30 and 0.88
                        val rFraction = (0.30f + (estDist / 3000f) * 0.58f).coerceIn(0.28f, 0.88f)
                        val radiusPx = (boxWidth.value / 2f * 0.94f) * rFraction
                        val offsetX = (radiusPx * cos(angleRad)).dp
                        val offsetY = (radiusPx * sin(angleRad)).dp

                        val (nodeIcon, nodeBg, nodeTint) = getPeerRoleDesign(index, peer, rescue)

                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .offset(x = offsetX, y = offsetY)
                                .testTag("radar_blip_${peer.peerId}")
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                // Pulsing animated ping ring
                                Box(
                                    modifier = Modifier
                                        .size((20 * pingAnim).dp)
                                        .clip(CircleShape)
                                        .background(nodeBg.copy(alpha = pingAlpha))
                                )
                                // Solid center icon badge
                                Box(
                                    modifier = Modifier
                                        .size(20.dp)
                                        .clip(CircleShape)
                                        .background(nodeBg),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = nodeIcon,
                                        contentDescription = peer.displayName,
                                        tint = nodeTint,
                                        modifier = Modifier.size(11.dp)
                                    )
                                }
                            }
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.surfaceContainerHigh),
                                shadowElevation = 1.dp,
                                modifier = Modifier.padding(top = 2.dp)
                            ) {
                                Text(
                                    text = "${peer.displayName.take(7)} • $distText",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Direction / Scale Sub-legend matching code_bf876aad.html lines 93-100
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .border(
                        BorderStroke(0.dp, Color.Transparent)
                    ),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Explore,
                        contentDescription = "Compass Heading",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = "Heading: 018° NNE",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Text(
                    text = "Outer Ring: 3000m",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun getPeerRoleDesign(
    index: Int,
    peer: Peer,
    rescue: com.example.ui.theme.RescueSemanticColors
): Triple<ImageVector, Color, Color> {
    return when (index % 4) {
        0 -> Triple(Icons.Filled.Shield, rescue.sosCrimson, Color.White)
        1 -> Triple(Icons.Filled.WbSunny, rescue.amberRelay, Color.Black)
        2 -> Triple(Icons.Filled.Person, Color(0xFF565E74), Color.White)
        else -> Triple(Icons.Filled.Router, Color(0xFF131B2E), Color.White)
    }
}

private fun formatDistance(meters: Int): String {
    return if (meters < 1000) {
        "${meters}m"
    } else {
        val km = meters / 1000.0
        String.format(Locale.US, "%.1fkm", km)
    }
}

private fun estimateDistanceMeters(rssi: Int): Int {
    return when {
        rssi >= -50 -> 250
        rssi >= -65 -> 600
        rssi >= -75 -> 1200
        rssi >= -85 -> 1900
        rssi >= -92 -> 2500
        else -> 2850
    }
}

private fun computePeerAngle(peerId: String, index: Int): Double {
    val predefinedAngles = listOf(45.0, 260.0, 135.0, 310.0, 190.0, 80.0)
    if (index < predefinedAngles.size) {
        return Math.toRadians(predefinedAngles[index])
    }
    val hash = peerId.hashCode().let { if (it < 0) -it else it }
    return Math.toRadians((hash % 360).toDouble())
}
