package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.domain.model.EmergencyReply
import com.example.domain.model.SessionStatus
import com.example.domain.model.VoiceTransportMode
import com.example.location.GeoUtils
import com.example.location.OfflineMapManager
import com.example.ui.components.CameraPhotoCaptureDialog
import com.example.ui.components.LocalGMapViewerCard
import com.example.ui.components.MicToggleButton
import com.example.ui.components.WhatsappMessageBubble
import com.example.ui.theme.LocalRescueColors
import com.example.ui.viewmodel.ActiveEmergencyUiState
import java.io.File

/**
 * Dual-mode two-way emergency session screen matching:
 * - Sender mode: sender_session_active_72f8f862.html (Proximity card + WhatsApp chat + Camera P7 photo button)
 * - Recipient mode: session_active_3b506e55.html (Status pill + WhatsApp chat + Location & Listen tiles, NO camera button)
 */
@Composable
fun SessionActiveScreen(
    uiState: ActiveEmergencyUiState,
    offlineMapManager: OfflineMapManager,
    resolveImageFile: (String?) -> File?,
    onBack: () -> Unit,
    onToggleMic: () -> Unit,
    onToggleSpeakerListen: () -> Unit,
    onSendReply: (String) -> Unit,
    onSendPhoto: (ByteArray) -> Unit,
    onRefreshLocation: () -> Unit,
    onResolveSession: () -> Unit,
    onRemoteSessionClosed: (String) -> Unit = {},
    onDismissBanner: () -> Unit,
    modifier: Modifier = Modifier
) {
    val rescue = LocalRescueColors.current
    val context = LocalContext.current
    val session = uiState.session
    val isSender = session?.isOutgoing == true

    BackHandler(onBack = onBack)

    var messageDraft by remember { mutableStateOf("") }
    // Recipient defaults to showing the interactive Local Google Maps Viewer immediately
    var showMapPanel by remember(session?.sessionId, isSender) { mutableStateOf(!isSender) }
    var showCameraDialog by remember { mutableStateOf(false) }
    var showResolveConfirm by remember { mutableStateOf(false) }
    var hasExitedSession by remember(session?.sessionId) { mutableStateOf(false) }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            onToggleMic()
        }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(uiState.replies.size) {
        if (uiState.replies.isNotEmpty()) {
            listState.animateScrollToItem(uiState.replies.lastIndex)
        }
    }

    LaunchedEffect(session?.status) {
        if (!hasExitedSession && session != null &&
            (session.status == SessionStatus.RESOLVED || session.status == SessionStatus.CANCELLED)
        ) {
            hasExitedSession = true
            onRemoteSessionClosed(session.sessionId)
            onBack()
        }
    }

    if (showCameraDialog && isSender) {
        CameraPhotoCaptureDialog(
            onPhotoCaptured = { jpeg ->
                showCameraDialog = false
                onSendPhoto(jpeg)
            },
            onDismiss = { showCameraDialog = false }
        )
    }

    if (showResolveConfirm) {
        AlertDialog(
            onDismissRequest = { showResolveConfirm = false },
            title = { Text("Terminate Emergency Session?") },
            text = {
                Text("This ends the emergency session on BOTH sender and recipient devices, stops all live microphone/speaker streams, and clears pending relays.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showResolveConfirm = false
                        onResolveSession()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = rescue.sosCrimson),
                    modifier = Modifier.testTag("confirm_resolve_button")
                ) {
                    Text("Terminate Session", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResolveConfirm = false }) {
                    Text("Stay Active")
                }
            }
        )
    }

    val proximity = GeoUtils.calculateProximity(
        from = if (isSender) session?.location else uiState.localLocation,
        to = if (isSender) session?.responderLocation else session?.location
    )

    val counterpartName = if (isSender) {
        uiState.acknowledgements
            .lastOrNull { it.isFinalDelivery }
            ?.let { "Responder #${it.fromPeerId.take(4)}" }
            ?: "Trusted Responder"
    } else {
        session?.creatorDisplayName ?: "Sender"
    }

    val voiceSubtitle = when (uiState.voiceTransportMode) {
        VoiceTransportMode.WEBRTC_LIVE -> "WebRTC Live"
        VoiceTransportMode.BLE_5S_LOOP -> "5s BLE Audio"
        VoiceTransportMode.MUTED -> "Mic Muted"
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .imePadding()
    ) {
        AnimatedVisibility(visible = uiState.statusBanner != null) {
            uiState.statusBanner?.let { banner ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .clickable(onClick = onDismissBanner),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Text(
                        text = banner,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }
        }

        // Top context header card matching each prototype
        if (isSender) {
            val initials = counterpartName.take(2).uppercase()
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .clickable { showMapPanel = !showMapPanel }
                    .testTag("sender_proximity_card"),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceContainer),
                shadowElevation = 2.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(modifier = Modifier.size(46.dp)) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = initials,
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.primaryContainer
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(Color.White)
                                    .padding(2.dp)
                                    .clip(CircleShape)
                                    .background(rescue.emeraldActive)
                            )
                        }
                        Column {
                            val distHeadline = if (proximity != null) {
                                "$counterpartName is ${proximity.distanceLabel} away"
                            } else {
                                "$counterpartName · Active"
                            }
                            Text(
                                text = distHeadline,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${proximity?.etaLabel ?: "Live Mesh"} • $voiceSubtitle",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        IconButton(
                            onClick = { showMapPanel = !showMapPanel },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.NearMe,
                                contentDescription = "Toggle offline map",
                                tint = rescue.emeraldActive,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        if (session?.status == SessionStatus.ACTIVE) {
                            Surface(
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .clip(CircleShape)
                                    .clickable { showResolveConfirm = true }
                                    .testTag("terminate_session_button"),
                                shape = CircleShape,
                                color = rescue.sosCrimson
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.CallEnd,
                                        contentDescription = "End emergency session",
                                        tint = Color.White,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = "End Session",
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                        color = Color.White
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // Recipient status pill + Clear "End Session" termination button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.surfaceContainerHigh),
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(rescue.emeraldActive)
                        )
                        Text(
                            text = "${counterpartName} · Connected",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (session?.status == SessionStatus.ACTIVE) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .clip(CircleShape)
                            .clickable { showResolveConfirm = true }
                            .testTag("terminate_session_button"),
                        shape = CircleShape,
                        color = rescue.sosCrimson
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.CallEnd,
                                contentDescription = "End emergency session",
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "End Session",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = Color.White
                            )
                        }
                    }
                }
            }
        }

        // Expandable Interactive Local Google Maps Viewer (Open by default for Recipient)
        AnimatedVisibility(visible = showMapPanel) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                LocalGMapViewerCard(
                    offlineMapManager = offlineMapManager,
                    senderLocation = session?.location,
                    responderLocation = if (isSender) session?.responderLocation else uiState.localLocation,
                    mapHeight = 180.dp
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (proximity != null) {
                            "${proximity.distanceLabel} ${proximity.compassCardinal} · ${proximity.etaLabel}"
                        } else {
                            "Waiting for peer GNSS fix..."
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(
                        onClick = onRefreshLocation,
                        enabled = !uiState.isRefreshingLocation,
                        modifier = Modifier.testTag("refresh_gps_button")
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (uiState.isRefreshingLocation) "Updating..." else "Ping GPS")
                    }
                }
            }
        }

        // Chat message thread (WhatsApp-inspired bubbles)
        val initialSystemBubble = remember(session?.sessionId, session?.context?.summary) {
            session?.let {
                EmergencyReply(
                    replyId = "sos_origin_${it.sessionId}",
                    sessionId = it.sessionId,
                    senderPeerId = it.creatorPeerId,
                    senderName = it.creatorDisplayName,
                    message = it.context.summary,
                    isIncoming = !it.isOutgoing,
                    deliveryStatus = it.deliveryStatus,
                    timestampMillis = it.createdAtMillis
                )
            }
        }
        val chatItems = remember(initialSystemBubble, uiState.replies) {
            buildList {
                if (initialSystemBubble != null && uiState.replies.none { it.message == initialSystemBubble.message }) {
                    add(initialSystemBubble)
                }
                addAll(uiState.replies)
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Bottom)
        ) {
            items(chatItems, key = { it.replyId }) { reply ->
                WhatsappMessageBubble(
                    reply = reply,
                    imageFile = resolveImageFile(reply.imageFileId)
                )
            }
        }

        // Recipient Two Quick-Action Tiles: Location & Listen
        if (!isSender) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .clickable {
                            showMapPanel = !showMapPanel
                            onRefreshLocation()
                        }
                        .testTag("recipient_location_tile"),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    border = BorderStroke(
                        1.dp,
                        if (showMapPanel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer
                    ),
                    shadowElevation = 1.dp
                ) {
                    Column(
                        modifier = Modifier.padding(vertical = 16.dp, horizontal = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(50.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.LocationOn,
                                contentDescription = "Location",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Location",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (proximity != null) {
                            Text(
                                text = "${proximity.distanceLabel} ${proximity.compassCardinal}",
                                style = MaterialTheme.typography.labelSmall,
                                color = rescue.emeraldActive
                            )
                        }
                    }
                }

                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onToggleSpeakerListen() }
                        .testTag("recipient_listen_tile"),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    border = BorderStroke(
                        width = if (uiState.isSpeakerListening) 2.dp else 1.dp,
                        color = if (uiState.isSpeakerListening) rescue.sosCrimson else MaterialTheme.colorScheme.surfaceContainer
                    ),
                    shadowElevation = 1.dp
                ) {
                    Column(
                        modifier = Modifier.padding(vertical = 16.dp, horizontal = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(50.dp)
                                .clip(CircleShape)
                                .background(
                                    if (uiState.isSpeakerListening) rescue.sosCrimson
                                    else MaterialTheme.colorScheme.errorContainer
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                                contentDescription = "Listen",
                                tint = if (uiState.isSpeakerListening) Color.White else rescue.sosCrimson,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Listen",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        val clipCount = uiState.audioClips.size
                        Text(
                            text = when {
                                uiState.playingClipId != null -> "Playing 5s clip"
                                uiState.isSpeakerListening && clipCount > 0 -> "Live ($clipCount clips)"
                                uiState.isSpeakerListening -> "Listening..."
                                else -> "Tap to hear"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (uiState.isSpeakerListening) rescue.sosCrimson else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Fixed Bottom Input Bar matching both prototypes
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MicToggleButton(
                    isMicActive = uiState.isMicStreaming,
                    onToggle = {
                        val hasAudioPerm = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                        if (uiState.isMicStreaming || hasAudioPerm) {
                            onToggleMic()
                        } else {
                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                )

                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceContainerHigh)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            if (messageDraft.isEmpty()) {
                                Text(
                                    text = if (isSender) "Message..." else "Message ${counterpartName.substringBefore(' ')}...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            BasicTextField(
                                value = messageDraft,
                                onValueChange = { messageDraft = it },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyMedium.copy(
                                    color = MaterialTheme.colorScheme.onSurface
                                ),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("session_message_input")
                            )
                        }
                        if (isSender) {
                            IconButton(
                                onClick = { showCameraDialog = true },
                                modifier = Modifier
                                    .size(34.dp)
                                    .testTag("camera_photo_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.CameraAlt,
                                    contentDescription = "Capture emergency photo",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }

                Surface(
                    modifier = Modifier
                        .size(46.dp)
                        .testTag("send_reply_button")
                        .clickable {
                            if (messageDraft.isNotBlank()) {
                                onSendReply(messageDraft)
                                messageDraft = ""
                            }
                        },
                    shape = RoundedCornerShape(14.dp),
                    color = if (isSender) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.primary
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}
