package com.example.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Report
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.domain.model.DeliveryStatus
import com.example.domain.model.SessionStatus
import com.example.ui.theme.LocalRescueColors
import kotlinx.coroutines.delay

@Composable
fun TacticalCard(
    modifier: Modifier = Modifier,
    borderColor: Color = LocalRescueColors.current.hairlineBorder.copy(alpha = 0.55f),
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(0.5.dp, borderColor)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            content = content
        )
    }
}

@Composable
fun DeliveryStatusPill(
    status: DeliveryStatus,
    modifier: Modifier = Modifier
) {
    val rescue = LocalRescueColors.current
    val (bg, fg, icon) = when (status) {
        DeliveryStatus.ACK_DELIVERED -> Triple(
            rescue.emeraldContainer,
            rescue.emeraldActive,
            Icons.Filled.CheckCircle
        )
        DeliveryStatus.ACK_STORED -> Triple(
            rescue.amberRelayContainer,
            rescue.amberRelay,
            Icons.Filled.Storage
        )
        DeliveryStatus.QUEUED_RELAY, DeliveryStatus.CREATED -> Triple(
            MaterialTheme.colorScheme.surfaceContainer,
            MaterialTheme.colorScheme.onSurfaceVariant,
            Icons.Filled.HourglassTop
        )
        DeliveryStatus.EXPIRED, DeliveryStatus.FAILED -> Triple(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.error,
            Icons.Filled.Report
        )
    }

    Surface(
        modifier = modifier,
        color = bg,
        shape = CircleShape
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(13.dp)
            )
            Text(
                text = status.label,
                style = MaterialTheme.typography.labelSmall,
                color = fg,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
fun DeliveryStageStepper(
    deliveryStatus: DeliveryStatus,
    sessionStatus: SessionStatus,
    relayStoredCount: Int,
    deliveredCount: Int
) {
    val rescue = LocalRescueColors.current
    val currentStage = when {
        sessionStatus == SessionStatus.RESOLVED || sessionStatus == SessionStatus.CANCELLED -> 4
        deliveryStatus == DeliveryStatus.ACK_DELIVERED || deliveredCount > 0 -> 3
        deliveryStatus == DeliveryStatus.ACK_STORED || relayStoredCount > 0 -> 2
        else -> 1
    }

    val stages = listOf(
        Triple(1, "Queued", MaterialTheme.colorScheme.primaryContainer),
        Triple(2, "Relay ($relayStoredCount)", rescue.amberRelay),
        Triple(3, "Delivered ($deliveredCount)", rescue.emeraldActive),
        Triple(4, "Resolved", MaterialTheme.colorScheme.onSurfaceVariant)
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        stages.forEach { (step, label, activeColor) ->
            val reached = currentStage >= step
            val barColor = if (reached) activeColor else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(barColor)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (reached) activeColor else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
fun HoldToSosButton(
    isActivating: Boolean,
    onSosTriggered: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val primaryColor = MaterialTheme.colorScheme.secondary
    val containerColor = MaterialTheme.colorScheme.secondaryContainer
    val glowColor = LocalRescueColors.current.sosCrimsonGlow
    val trackColor = MaterialTheme.colorScheme.outline

    var isPressed by remember { mutableStateOf(false) }
    var holdProgress by remember { mutableFloatStateOf(0f) }

    val pulseTransition = rememberInfiniteTransition(label = "sos_pulse")
    val pulseScale by pulseTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )
    val animatedProgress by animateFloatAsState(
        targetValue = holdProgress,
        animationSpec = spring(),
        label = "hold_progress"
    )

    LaunchedEffect(isPressed) {
        if (!isPressed) {
            holdProgress = 0f
            return@LaunchedEffect
        }
        val totalMillis = 2000L
        val stepMillis = 40L
        var elapsed = 0L
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        while (isPressed && elapsed < totalMillis) {
            delay(stepMillis)
            elapsed += stepMillis
            holdProgress = (elapsed.toFloat() / totalMillis).coerceIn(0f, 1f)
            if (elapsed == 1000L) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
        }
        if (isPressed && holdProgress >= 1f) {
            isPressed = false
            holdProgress = 0f
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onSosTriggered()
        }
    }

    val remainingSeconds = ((1f - holdProgress) * 2f).coerceAtLeast(0f)

    Box(
        modifier = modifier
            .size(210.dp)
            .testTag("sos_hold_button")
            .semantics {
                role = Role.Button
                contentDescription = "Emergency SOS button. Press and hold for 2 seconds to activate."
            }
            .pointerInput(isActivating) {
                if (!isActivating) {
                    detectTapGestures(
                        onPress = {
                            isPressed = true
                            tryAwaitRelease()
                            isPressed = false
                        }
                    )
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val radius = size.minDimension / 2f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        primaryColor.copy(alpha = if (isPressed) 0.34f else 0.16f),
                        Color.Transparent
                    )
                ),
                radius = radius * pulseScale
            )
            drawCircle(
                color = trackColor.copy(alpha = 0.45f),
                radius = radius - 14.dp.toPx(),
                style = Stroke(width = 4.dp.toPx())
            )
            if (animatedProgress > 0.01f) {
                drawArc(
                    color = glowColor,
                    startAngle = -90f,
                    sweepAngle = animatedProgress * 360f,
                    useCenter = false,
                    style = Stroke(width = 7.dp.toPx(), cap = StrokeCap.Round)
                )
            }
        }

        Surface(
            modifier = Modifier.size(156.dp),
            shape = CircleShape,
            color = if (isPressed) primaryColor else containerColor,
            border = BorderStroke(1.5.dp, if (isPressed) glowColor else primaryColor.copy(alpha = 0.85f)),
            shadowElevation = 8.dp
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Radar,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = if (isActivating) "SENDING" else "SOS",
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = when {
                        isActivating -> "Locating..."
                        isPressed -> "Hold %.1fs".format(remainingSeconds)
                        else -> "HOLD 2s"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
