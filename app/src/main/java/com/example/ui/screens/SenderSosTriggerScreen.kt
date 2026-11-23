package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.ShareLocation
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.domain.model.DeliveryStatus
import com.example.domain.model.SessionStatus
import com.example.domain.model.VoiceTransportMode
import com.example.ui.components.SosWaitingRadarOrb
import com.example.ui.theme.LocalRescueColors
import com.example.ui.viewmodel.ActiveEmergencyUiState
import kotlin.math.roundToInt

/**
 * Matches sender_sos_trigger_a5433ad5.html:
 * Pulsing radar orb, "Waiting for session", real mesh DeliveryStatus progression,
 * 3-card Sharing Deck (Location, Live Audio, Mesh E2EE — no Medical ID), Cancel SOS button,
 * and automatic transition to SessionActive when a paired contact accepts the SOS.
 */
@Composable
fun SenderSosTriggerScreen(
    uiState: ActiveEmergencyUiState,
    onSessionAccepted: (String) -> Unit,
    onCancelSos: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val rescue = LocalRescueColors.current
    val session = uiState.session
    var showCancelConfirm by remember { mutableStateOf(false) }

    BackHandler(onBack = onBack)

    LaunchedEffect(session?.sessionAccepted, session?.status) {
        if (session != null && session.status == SessionStatus.ACTIVE && session.sessionAccepted) {
            onSessionAccepted(session.sessionId)
        } else if (session != null && (session.status == SessionStatus.CANCELLED || session.status == SessionStatus.RESOLVED)) {
            onBack()
        }
    }

    if (showCancelConfirm) {
        AlertDialog(
            onDismissRequest = { showCancelConfirm = false },
            title = { Text("Cancel SOS Broadcast?") },
            text = {
                Text("This stops the SOS beacon spray and notifies any nearby relays that the alert is cancelled.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showCancelConfirm = false
                        onCancelSos()
                        onBack()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = rescue.sosCrimson),
                    modifier = Modifier.testTag("confirm_cancel_sos_button")
                ) {
                    Text("Cancel SOS")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCancelConfirm = false }) {
                    Text("Keep Spraying")
                }
            }
        )
    }

    val deliveredToRelayOrTarget = session != null && (
        session.deliveryStatus == DeliveryStatus.ACK_STORED ||
            session.deliveryStatus == DeliveryStatus.ACK_DELIVERED ||
            session.relayStoredCount > 0 ||
            session.deliveredCount > 0
        )

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Upper radar + status section
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            SosWaitingRadarOrb(delivered = deliveredToRelayOrTarget)

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = when {
                    session?.status == SessionStatus.CANCELLED -> "SOS cancelled"
                    session?.sessionAccepted == true -> "Target responder accepted"
                    else -> "Waiting for session"
                },
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            val statusLabel = when {
                session == null -> "Initializing beacon..."
                session.sessionAccepted ->
                    "Responder accepted · Opening session..."
                session.deliveryStatus == DeliveryStatus.ACK_DELIVERED ->
                    "Alert delivered to responder · Waiting for acceptance..."
                session.deliveryStatus == DeliveryStatus.ACK_STORED || session.relayStoredCount > 0 ->
                    "Sent successfully · Held by ${maxOf(1, session.relayStoredCount)} relay node(s)"
                uiState.nearbyPeerCount > 0 ->
                    "Status: Spraying encrypted SOS to ${uiState.nearbyPeerCount} nearby node(s)"
                else ->
                    "Status: Waiting for a node to receive"
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.padding(horizontal = 8.dp)
            ) {
                if (deliveredToRelayOrTarget) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(rescue.emeraldActive)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(
                    text = statusLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (deliveredToRelayOrTarget) rescue.emeraldActive else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }

        // Middle "SHARING" 3-card grid (Location, Live Audio, Mesh E2EE)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "SHARING",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(rescue.emeraldActive)
                    )
                    Text(
                        text = "Live sync",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val locText = session?.location?.let { "±${it.accuracyMeters.roundToInt()}m" } ?: "GPS fix..."
                SharingMetricTile(
                    icon = Icons.Filled.ShareLocation,
                    title = "Location",
                    subtitle = locText,
                    modifier = Modifier.weight(1f)
                )

                val audioSub = when (uiState.voiceTransportMode) {
                    VoiceTransportMode.WEBRTC_LIVE -> "WebRTC Live"
                    VoiceTransportMode.BLE_5S_LOOP -> "5s BLE Loop"
                    VoiceTransportMode.MUTED -> "Armed · On accept"
                }
                SharingMetricTile(
                    icon = Icons.Filled.Mic,
                    title = "Live Audio",
                    subtitle = audioSub,
                    modifier = Modifier.weight(1f)
                )

                val e2eeSub = if (uiState.pairedContactsCount > 0) {
                    "${uiState.pairedContactsCount} paired"
                } else {
                    "Unlimited hops"
                }
                SharingMetricTile(
                    icon = Icons.Filled.Lock,
                    title = "Mesh E2EE",
                    subtitle = e2eeSub,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Bottom Cancel SOS button
        OutlinedButton(
            onClick = { showCancelConfirm = true },
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, rescue.sosCrimson.copy(alpha = 0.28f)),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                contentColor = rescue.sosCrimson
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .testTag("cancel_sos_button")
        ) {
            Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Cancel SOS",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
            )
        }
    }
}

@Composable
private fun SharingMetricTile(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    val rescue = LocalRescueColors.current
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceContainer),
        shadowElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = rescue.sosCrimson,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}
