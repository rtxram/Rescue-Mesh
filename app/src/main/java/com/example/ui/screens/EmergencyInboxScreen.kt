package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.domain.model.EmergencySession
import com.example.domain.model.SessionStatus
import com.example.ui.components.DeliveryStatusPill
import com.example.ui.components.TacticalCard
import com.example.ui.theme.LocalRescueColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun EmergencyInboxScreen(
    sessions: List<EmergencySession>,
    onOpenSession: (EmergencySession) -> Unit,
    onAcknowledgeSession: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val rescue = LocalRescueColors.current
    val timeFmt = remember { SimpleDateFormat("MMM d · HH:mm", Locale.getDefault()) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Column {
                Text(
                    text = "Emergency Inbox",
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold)
                )
                Text(
                    text = "Decrypted incoming SOS alerts and active mesh sessions",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (sessions.isEmpty()) {
            item {
                TacticalCard(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Filled.Inbox,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "No emergency sessions yet",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Outgoing SOS beacons and decrypted alerts from paired contacts appear here.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        } else {
            items(sessions, key = { it.sessionId }) { session ->
                val border = when {
                    session.status == SessionStatus.ACTIVE && session.unread ->
                        rescue.sosCrimson.copy(alpha = 0.65f)
                    session.status == SessionStatus.ACTIVE ->
                        rescue.amberRelay.copy(alpha = 0.45f)
                    else -> rescue.hairlineBorder.copy(alpha = 0.55f)
                }
                TacticalCard(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                    borderColor = border,
                    modifier = Modifier
                        .clickable { onOpenSession(session) }
                        .testTag("inbox_session_${session.sessionId}")
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Surface(
                                color = if (session.isOutgoing) MaterialTheme.colorScheme.surfaceContainerHigh
                                else MaterialTheme.colorScheme.errorContainer,
                                shape = CircleShape
                            ) {
                                Text(
                                    text = if (session.isOutgoing) "Sent" else "Incoming",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (session.isOutgoing) MaterialTheme.colorScheme.onSurface
                                    else rescue.sosCrimson,
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                                )
                            }
                            if (session.unread) {
                                Surface(
                                    color = rescue.sosCrimson,
                                    shape = CircleShape
                                ) {
                                    Text(
                                        text = "New",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onError,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                        DeliveryStatusPill(status = session.deliveryStatus)
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "${session.creatorDisplayName} · ${session.context.category}",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = session.context.summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Hop ${session.hopCount} · ${session.status.label} · ${timeFmt.format(Date(session.updatedAtMillis))}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (!session.isOutgoing && session.status == SessionStatus.ACTIVE && !session.sessionAccepted) {
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = { onAcknowledgeSession(session.sessionId) },
                            border = BorderStroke(1.dp, rescue.emeraldActive),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = rescue.emeraldActive),
                            modifier = Modifier.testTag("inbox_ack_${session.sessionId}")
                        ) {
                            Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Acknowledge & Open Session")
                        }
                    }
                }
            }
        }
    }
}
