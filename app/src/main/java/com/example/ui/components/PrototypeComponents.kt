package com.example.ui.components

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShareLocation
import androidx.compose.material.icons.filled.Tune
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.domain.model.DeliveryStatus
import com.example.domain.model.EmergencyReply
import com.example.domain.model.LocationSnapshot
import com.example.location.GeoUtils
import com.example.location.MapTileStyle
import com.example.location.OfflineMapManager
import com.example.security.QrPairingCodec
import com.example.ui.theme.LocalRescueColors
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Clean prototype header: Hamburger Menu on left, centered screen title with optional crimson live dot,
 * Settings icon on right.
 */
@Composable
fun PrototypeTopAppBar(
    title: String,
    showLiveDot: Boolean = false,
    unreadInboxCount: Int = 0,
    onOpenDrawer: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val rescue = LocalRescueColors.current
    val pulseTransition = rememberInfiniteTransition(label = "live_dot")
    val dotScale by pulseTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "dot_scale"
    )

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .height(56.dp)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Box {
                    IconButton(
                        onClick = onOpenDrawer,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("header_menu_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Menu,
                            contentDescription = "Open navigation menu",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    if (unreadInboxCount > 0) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = 8.dp, end = 8.dp)
                                .size(9.dp)
                                .clip(CircleShape)
                                .background(rescue.sosCrimson)
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (showLiveDot) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .scale(dotScale)
                                .clip(CircleShape)
                                .background(rescue.sosCrimson)
                        )
                    }
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                IconButton(
                    onClick = onOpenSettings,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("header_settings_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = "Settings",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f))
        }
    }
}

enum class HomeFooterTab(val label: String, val icon: ImageVector, val tag: String) {
    HOME("Home", Icons.Filled.RadioButtonChecked, "nav_home"),
    NODES("Nodes", Icons.Filled.Hub, "nav_peers"),
    CONFIG("Config", Icons.Filled.Tune, "nav_config")
}

@Composable
fun PrototypeBottomBar(
    selectedTab: HomeFooterTab,
    nearbyCount: Int,
    onSelectTab: (HomeFooterTab) -> Unit,
    modifier: Modifier = Modifier
) {
    val rescue = LocalRescueColors.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        shadowElevation = 4.dp
    ) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.75f))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .height(64.dp)
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                HomeFooterTab.entries.forEach { tab ->
                    val isSelected = tab == selectedTab
                    val tint = if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
                    Column(
                        modifier = Modifier
                            .testTag(tab.tag)
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { onSelectTab(tab) }
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = tab.label,
                                tint = tint,
                                modifier = Modifier.size(24.dp)
                            )
                            if (tab == HomeFooterTab.NODES && nearbyCount > 0) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(rescue.emeraldActive)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = tab.label.uppercase(),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                letterSpacing = 0.8.sp
                            ),
                            color = tint
                        )
                    }
                }
            }
        }
    }
}

/**
 * Signature prototype SOS orb matching sender_home_f506546c.html:
 * Concentric red ambient rings + 192dp crimson gradient sphere + 2-second circular progress sweep.
 */
@Composable
fun HoldToSosOrb(
    isActivating: Boolean,
    onSosTriggered: () -> Unit,
    modifier: Modifier = Modifier
) {
    val rescue = LocalRescueColors.current
    val haptic = LocalHapticFeedback.current
    var isPressed by remember { mutableStateOf(false) }
    var holdProgress by remember { mutableFloatStateOf(0f) }

    val pulseTransition = rememberInfiniteTransition(label = "home_sos_pulse")
    val ringScale by pulseTransition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(tween(2400), RepeatMode.Reverse),
        label = "ring_scale"
    )
    val animatedProgress by animateFloatAsState(
        targetValue = holdProgress,
        animationSpec = spring(),
        label = "hold_sweep"
    )

    LaunchedEffect(isPressed) {
        if (!isPressed) {
            holdProgress = 0f
            return@LaunchedEffect
        }
        val totalMs = 2000L
        val stepMs = 40L
        var elapsed = 0L
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        while (isPressed && elapsed < totalMs) {
            delay(stepMs)
            elapsed += stepMs
            holdProgress = (elapsed.toFloat() / totalMs).coerceIn(0f, 1f)
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

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .size(220.dp)
                .testTag("sos_hold_button")
                .semantics {
                    role = Role.Button
                    contentDescription = "Hold for 2 seconds to trigger emergency SOS beacon"
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
            // 3 concentric subtle crimson ambient rings
            Box(
                modifier = Modifier
                    .size((216 * ringScale).dp)
                    .clip(CircleShape)
                    .background(rescue.sosCrimson.copy(alpha = 0.045f))
            )
            Box(
                modifier = Modifier
                    .size(196.dp)
                    .clip(CircleShape)
                    .background(rescue.sosCrimson.copy(alpha = 0.08f))
            )
            Box(
                modifier = Modifier
                    .size(172.dp)
                    .clip(CircleShape)
                    .background(rescue.sosCrimson.copy(alpha = 0.13f))
            )

            // Main 152dp gradient circle with specular sheen & white progress ring
            Box(
                modifier = Modifier
                    .size(if (isPressed) 146.dp else 152.dp)
                    .shadow(
                        elevation = 16.dp,
                        shape = CircleShape,
                        ambientColor = rescue.sosCrimson.copy(alpha = 0.35f),
                        spotColor = rescue.sosCrimson.copy(alpha = 0.38f)
                    )
                    .clip(CircleShape)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(rescue.sosCrimsonGlow, rescue.sosCrimson)
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                // Specular top sheen
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 6.dp)
                        .size(width = 110.dp, height = 38.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.16f))
                )

                Canvas(modifier = Modifier.fillMaxSize().padding(6.dp)) {
                    drawCircle(
                        color = Color.White.copy(alpha = 0.20f),
                        style = Stroke(width = 5.dp.toPx())
                    )
                    if (animatedProgress > 0.01f) {
                        drawArc(
                            color = Color.White,
                            startAngle = -90f,
                            sweepAngle = animatedProgress * 360f,
                            useCenter = false,
                            style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round)
                        )
                    }
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Sensors,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(30.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (isActivating) "WAIT" else "SOS",
                        style = MaterialTheme.typography.displaySmall.copy(
                            fontSize = 38.sp,
                            letterSpacing = 5.sp
                        ),
                        color = Color.White
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = when {
                isActivating -> "Acquiring GPS & sealing mesh beacon..."
                isPressed -> "Transmitting distress in %.1fs...".format(remainingSeconds)
                else -> "Hold for 2 seconds to activate"
            },
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = 13.sp,
                fontWeight = if (isPressed) FontWeight.SemiBold else FontWeight.Medium
            ),
            color = if (isPressed) rescue.sosCrimson else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Expanding radar wave animation matching sender_sos_trigger_a5433ad5.html.
 */
@Composable
fun SosWaitingRadarOrb(
    delivered: Boolean,
    modifier: Modifier = Modifier
) {
    val rescue = LocalRescueColors.current
    val transition = rememberInfiniteTransition(label = "waiting_radar")
    val wave1 by transition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart),
        label = "wave1"
    )
    val wave2 by transition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(tween(2400, delayMillis = 800, easing = LinearEasing), RepeatMode.Restart),
        label = "wave2"
    )
    val wave3 by transition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(tween(2400, delayMillis = 1600, easing = LinearEasing), RepeatMode.Restart),
        label = "wave3"
    )

    val coreColor = if (delivered) rescue.emeraldActive else rescue.sosCrimson

    Box(
        modifier = modifier.size(176.dp),
        contentAlignment = Alignment.Center
    ) {
        listOf(wave1, wave2, wave3).forEach { w ->
            val alpha = (1.25f - w).coerceIn(0f, 0.45f)
            Surface(
                modifier = Modifier
                    .size(132.dp)
                    .scale(w),
                shape = CircleShape,
                color = coreColor.copy(alpha = alpha * 0.25f),
                border = BorderStroke(1.dp, coreColor.copy(alpha = alpha))
            ) {}
        }

        Box(
            modifier = Modifier
                .size(96.dp)
                .shadow(14.dp, CircleShape, spotColor = coreColor.copy(alpha = 0.35f))
                .clip(CircleShape)
                .background(
                    Brush.verticalGradient(
                        if (delivered) listOf(rescue.emeraldActive, rescue.forestContainer)
                        else listOf(rescue.sosCrimsonBright, rescue.sosCrimson)
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (delivered) Icons.Filled.CheckCircle else Icons.Filled.Sensors,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(40.dp)
            )
        }
    }
}

/**
 * Compatibility alias delegating to the interactive Google Maps Style Local Viewer.
 */
@Composable
fun OfflineOsmMapCard(
    offlineMapManager: OfflineMapManager,
    senderLocation: LocationSnapshot?,
    responderLocation: LocationSnapshot?,
    modifier: Modifier = Modifier
) {
    LocalGMapViewerCard(
        offlineMapManager = offlineMapManager,
        senderLocation = senderLocation,
        responderLocation = responderLocation,
        modifier = modifier
    )
}

/**
 * Interactive Google-Maps-style local map viewer for recipient (and optional sender drawer):
 * - Renders pre-cached local Google Map tiles (Roadmap/Hybrid z=13..16) with drag/pan + zoom controls
 * - Crisp Google-Maps street palette fallback when tiles aren't downloaded yet
 * - Victim SOS crimson pin + halo, Responder GPS blue dot, dashed bearing vector, distance/ETA HUD
 * - Optional fullscreen expansion + "Open in Google Maps" system intent
 */
@Composable
fun LocalGMapViewerCard(
    offlineMapManager: OfflineMapManager,
    senderLocation: LocationSnapshot?,
    responderLocation: LocationSnapshot?,
    mapHeight: Dp = 188.dp,
    allowExpandFullscreen: Boolean = true,
    modifier: Modifier = Modifier
) {
    val rescue = LocalRescueColors.current
    val context = LocalContext.current
    val cacheStatus by offlineMapManager.status.collectAsStateWithLifecycle()

    var zoom by remember { mutableIntStateOf(15) }
    var tileStyle by remember(cacheStatus.style) { mutableStateOf(cacheStatus.style) }
    var panOffsetX by remember { mutableFloatStateOf(0f) }
    var panOffsetY by remember { mutableFloatStateOf(0f) }
    var showFullscreen by remember { mutableStateOf(false) }

    val baseLat = senderLocation?.latitude
        ?: responderLocation?.latitude
        ?: cacheStatus.lastCachedCenterLat
        ?: 21.2514
    val baseLon = senderLocation?.longitude
        ?: responderLocation?.longitude
        ?: cacheStatus.lastCachedCenterLon
        ?: 81.6296

    val tileSizePx = 256f
    val centerFracBase = remember(baseLat, baseLon, zoom) {
        OfflineMapManager.latLonToFractionalTile(baseLat, baseLon, zoom)
    }
    val shiftedFracX = centerFracBase.first - (panOffsetX / tileSizePx)
    val shiftedFracY = centerFracBase.second - (panOffsetY / tileSizePx)
    val cx = floor(shiftedFracX).toInt()
    val cy = floor(shiftedFracY).toInt()

    val bitmaps = remember(cx, cy, zoom, tileStyle, cacheStatus.cachedTileCount) {
        val loaded = mutableMapOf<Pair<Int, Int>, Bitmap?>()
        for (dx in -2..2) {
            for (dy in -2..2) {
                val tx = cx + dx
                val ty = cy + dy
                loaded[tx to ty] = offlineMapManager.loadTileBitmap(zoom, tx, ty, tileStyle)
            }
        }
        loaded
    }

    val bearingDeg = if (senderLocation != null && responderLocation != null) {
        GeoUtils.bearingDegrees(
            responderLocation.latitude,
            responderLocation.longitude,
            senderLocation.latitude,
            senderLocation.longitude
        )
    } else null

    val distanceLabel = if (senderLocation != null && responderLocation != null) {
        val km = GeoUtils.distanceKm(
            responderLocation.latitude,
            responderLocation.longitude,
            senderLocation.latitude,
            senderLocation.longitude
        )
        "${GeoUtils.formatDistance(km)} ${GeoUtils.cardinalFromDegrees(bearingDeg ?: 0)}"
    } else null

    if (showFullscreen && allowExpandFullscreen) {
        Dialog(
            onDismissRequest = { showFullscreen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding(),
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Local Google Maps Viewer",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = if (cacheStatus.cachedTileCount > 0) {
                                    "${cacheStatus.cachedTileCount} cached offline tiles · ${tileStyle.label}"
                                } else {
                                    "Offline Vector Grid (download local G-Map in Config)"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { showFullscreen = false }) {
                            Icon(Icons.Filled.Close, contentDescription = "Close map")
                        }
                    }
                    LocalGMapViewerCard(
                        offlineMapManager = offlineMapManager,
                        senderLocation = senderLocation,
                        responderLocation = responderLocation,
                        mapHeight = 520.dp,
                        allowExpandFullscreen = false,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("local_gmap_viewer_card"),
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFFF3F1EA),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceContainerHigh),
        shadowElevation = 2.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(mapHeight)
                .clip(RoundedCornerShape(18.dp))
                .pointerInput(zoom) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        panOffsetX += dragAmount.x
                        panOffsetY += dragAmount.y
                    }
                }
        ) {
            val gmapLandBg = Color(0xFFF3F1EA)
            val gmapParkColor = Color(0xFFD9ECD0)
            val gmapMajorRoad = Color(0xFFFFE082)
            val gmapMinorRoad = Color(0xFFFFFFFF)
            val vectorColor = rescue.sosCrimson
            val senderColor = rescue.sosCrimson
            val responderBlue = Color(0xFF1A73E8) // Authentic Google Maps blue dot

            Canvas(modifier = Modifier.fillMaxSize()) {
                drawRect(color = gmapLandBg)

                val offsetX = size.width / 2f - ((shiftedFracX - cx).toFloat() * tileSizePx)
                val offsetY = size.height / 2f - ((shiftedFracY - cy).toFloat() * tileSizePx)

                var drawnAnyTile = false
                for ((coord, bmp) in bitmaps) {
                    if (bmp != null) {
                        drawnAnyTile = true
                        val left = offsetX + (coord.first - cx) * tileSizePx
                        val top = offsetY + (coord.second - cy) * tileSizePx
                        drawImage(
                            image = bmp.asImageBitmap(),
                            dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                            dstSize = IntSize(tileSizePx.roundToInt(), tileSizePx.roundToInt())
                        )
                    }
                }

                // Authentic Google Maps street-style vector fallback grid when tiles aren't cached
                if (!drawnAnyTile) {
                    drawRect(
                        color = gmapParkColor.copy(alpha = 0.7f),
                        topLeft = Offset(size.width * 0.08f, size.height * 0.14f),
                        size = androidx.compose.ui.geometry.Size(size.width * 0.28f, size.height * 0.34f)
                    )
                    drawRect(
                        color = gmapParkColor.copy(alpha = 0.6f),
                        topLeft = Offset(size.width * 0.68f, size.height * 0.45f),
                        size = androidx.compose.ui.geometry.Size(size.width * 0.24f, size.height * 0.30f)
                    )
                    val minorStep = 34.dp.toPx()
                    var x = (panOffsetX % minorStep)
                    while (x < size.width) {
                        drawLine(gmapMinorRoad, Offset(x, 0f), Offset(x, size.height), strokeWidth = 3.dp.toPx())
                        x += minorStep
                    }
                    var y = (panOffsetY % minorStep)
                    while (y < size.height) {
                        drawLine(gmapMinorRoad, Offset(0f, y), Offset(size.width, y), strokeWidth = 3.dp.toPx())
                        y += minorStep
                    }
                    // Highlight arterial roads
                    drawLine(
                        color = gmapMajorRoad,
                        start = Offset(0f, size.height * 0.36f + (panOffsetY % 60f)),
                        end = Offset(size.width, size.height * 0.42f + (panOffsetY % 60f)),
                        strokeWidth = 6.dp.toPx()
                    )
                    drawLine(
                        color = gmapMajorRoad,
                        start = Offset(size.width * 0.48f + (panOffsetX % 60f), 0f),
                        end = Offset(size.width * 0.54f + (panOffsetX % 60f), size.height),
                        strokeWidth = 6.dp.toPx()
                    )
                }

                fun toCanvasOffset(loc: LocationSnapshot): Offset {
                    val f = OfflineMapManager.latLonToFractionalTile(loc.latitude, loc.longitude, zoom)
                    return Offset(
                        x = size.width / 2f + (f.first - shiftedFracX).toFloat() * tileSizePx,
                        y = size.height / 2f + (f.second - shiftedFracY).toFloat() * tileSizePx
                    )
                }

                val sOffset = senderLocation?.let { toCanvasOffset(it) }
                val rOffset = responderLocation?.let { toCanvasOffset(it) }

                if (sOffset != null && rOffset != null) {
                    drawLine(
                        color = responderBlue.copy(alpha = 0.85f),
                        start = rOffset,
                        end = sOffset,
                        strokeWidth = 3.5.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 8f))
                    )
                }

                if (rOffset != null) {
                    drawCircle(responderBlue.copy(alpha = 0.18f), radius = 16.dp.toPx(), center = rOffset)
                    drawCircle(Color.White, radius = 9.dp.toPx(), center = rOffset)
                    drawCircle(responderBlue, radius = 6.5.dp.toPx(), center = rOffset)
                }
                if (sOffset != null) {
                    drawCircle(senderColor.copy(alpha = 0.20f), radius = 18.dp.toPx(), center = sOffset)
                    drawCircle(Color.White, radius = 10.dp.toPx(), center = sOffset)
                    drawCircle(senderColor, radius = 7.dp.toPx(), center = sOffset)
                }
            }

            // Top-left Google Maps layer / source badge
            Surface(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .clickable {
                        tileStyle = if (tileStyle == MapTileStyle.GMAPS_ROADMAP) {
                            MapTileStyle.GMAPS_HYBRID
                        } else {
                            MapTileStyle.GMAPS_ROADMAP
                        }
                    },
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                shadowElevation = 2.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Layers,
                        contentDescription = "Switch map layer",
                        tint = Color(0xFF1A73E8),
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = if (cacheStatus.cachedTileCount > 0) "Local G-Map z$zoom" else "G-Map Offline z$zoom",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            // Top-right map action controls: Recenter SOS, Zoom Out, Zoom In, Fullscreen / Open GMaps
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                        shadowElevation = 2.dp
                    ) {
                        IconButton(
                            onClick = {
                                panOffsetX = 0f
                                panOffsetY = 0f
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.MyLocation,
                                contentDescription = "Center on SOS",
                                tint = rescue.sosCrimson,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                        shadowElevation = 2.dp
                    ) {
                        IconButton(
                            onClick = { zoom = (zoom - 1).coerceAtLeast(13) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Filled.Remove, contentDescription = "Zoom out", modifier = Modifier.size(16.dp))
                        }
                    }
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                        shadowElevation = 2.dp
                    ) {
                        IconButton(
                            onClick = { zoom = (zoom + 1).coerceAtMost(16) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = "Zoom in", modifier = Modifier.size(16.dp))
                        }
                    }
                    if (allowExpandFullscreen) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                            shadowElevation = 2.dp
                        ) {
                            IconButton(
                                onClick = { showFullscreen = true },
                                modifier = Modifier
                                    .size(32.dp)
                                    .testTag("expand_gmap_viewer_button")
                            ) {
                                Icon(Icons.Filled.Fullscreen, contentDescription = "Expand Google Map viewer", modifier = Modifier.size(17.dp))
                            }
                        }
                    }
                }

                if (senderLocation != null) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                        shadowElevation = 2.dp,
                        modifier = Modifier.clickable {
                            val uri = Uri.parse(
                                "geo:${senderLocation.latitude},${senderLocation.longitude}?q=${senderLocation.latitude},${senderLocation.longitude}(RescueMesh+SOS)"
                            )
                            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            runCatching { context.startActivity(intent) }
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.OpenInNew,
                                contentDescription = "Open in Google Maps app",
                                tint = Color(0xFF1A73E8),
                                modifier = Modifier.size(12.dp)
                            )
                            Text(
                                text = "Maps",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = Color(0xFF1A73E8)
                            )
                        }
                    }
                }
            }

            // Bottom gradient overlay + coordinate & compass pill
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.90f))
                        )
                    )
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ShareLocation,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            text = if (senderLocation != null) {
                                GeoUtils.formatCoords(senderLocation.latitude, senderLocation.longitude)
                            } else {
                                "Acquiring GNSS..."
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            maxLines = 1
                        )
                    }
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.92f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = when {
                                bearingDeg != null && distanceLabel != null -> "$distanceLabel · ${bearingDeg}°"
                                bearingDeg != null -> "Compass ${bearingDeg}°"
                                else -> "Pin: Crimson = SOS · Blue = You"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * WhatsApp-style bubble supporting both text messages, inline compressed emergency camera photos,
 * sender names, timestamps, and emerald/mint delivery checkmarks (`done_all`).
 */
@Composable
fun WhatsappMessageBubble(
    reply: EmergencyReply,
    imageFile: File?,
    modifier: Modifier = Modifier
) {
    val rescue = LocalRescueColors.current
    val timeStr = remember(reply.timestampMillis) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(reply.timestampMillis))
    }
    val bitmap = remember(imageFile?.absolutePath) {
        imageFile?.takeIf { it.exists() }?.let { runCatching { BitmapFactory.decodeFile(it.absolutePath) }.getOrNull() }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = if (reply.isIncoming) Alignment.Start else Alignment.End
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 290.dp),
            shape = if (reply.isIncoming) {
                RoundedCornerShape(topStart = 4.dp, topEnd = 18.dp, bottomEnd = 18.dp, bottomStart = 18.dp)
            } else {
                RoundedCornerShape(topStart = 18.dp, topEnd = 4.dp, bottomEnd = 18.dp, bottomStart = 18.dp)
            },
            color = if (reply.isIncoming) {
                MaterialTheme.colorScheme.surfaceContainerLowest
            } else {
                MaterialTheme.colorScheme.primaryContainer
            },
            border = if (reply.isIncoming) {
                BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.8f))
            } else null,
            shadowElevation = 1.dp
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                if (reply.isIncoming) {
                    Text(
                        text = reply.senderName,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = rescue.emeraldActive
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Emergency mesh camera snapshot",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                            .clip(RoundedCornerShape(10.dp))
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }
                Text(
                    text = reply.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (reply.isIncoming) MaterialTheme.colorScheme.onSurface else Color.White
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = timeStr,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (reply.isIncoming) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        }
                    )
                    if (!reply.isIncoming) {
                        val delivered = reply.deliveryStatus == DeliveryStatus.ACK_DELIVERED
                        val relayed = reply.deliveryStatus == DeliveryStatus.ACK_STORED
                        Icon(
                            imageVector = Icons.Filled.DoneAll,
                            contentDescription = reply.deliveryStatus.label,
                            tint = when {
                                delivered -> rescue.mintTick
                                relayed -> rescue.amberRelay
                                else -> MaterialTheme.colorScheme.onPrimaryContainer
                            },
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MicToggleButton(
    isMicActive: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val rescue = LocalRescueColors.current
    Surface(
        modifier = modifier
            .size(46.dp)
            .testTag("mic_toggle_button")
            .clickable(onClick = onToggle),
        shape = RoundedCornerShape(14.dp),
        color = if (isMicActive) rescue.emeraldActive else MaterialTheme.colorScheme.errorContainer
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = if (isMicActive) Icons.Filled.Mic else Icons.Filled.MicOff,
                contentDescription = if (isMicActive) "Mute continuous mic stream" else "Unmute continuous mic stream",
                tint = if (isMicActive) Color.White else rescue.sosCrimson,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

/**
 * In-app CameraX QR Scanner Dialog for zero-storage-permission contact pairing.
 */
@Composable
fun QrScannerDialog(
    onQrDecoded: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasCameraPerm by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPerm = granted }

    LaunchedEffect(Unit) {
        if (!hasCameraPerm) {
            launcher.launch(Manifest.permission.CAMERA)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .clip(RoundedCornerShape(24.dp)),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Scan Contact Pairing QR",
                        style = MaterialTheme.typography.titleLarge
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "Close scanner")
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                if (hasCameraPerm) {
                    val executor = remember { Executors.newSingleThreadExecutor() }
                    var decodedOnce by remember { mutableStateOf(false) }
                    DisposableEffect(Unit) {
                        onDispose { executor.shutdown() }
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp)
                            .clip(RoundedCornerShape(16.dp))
                    ) {
                        AndroidView(
                            factory = { ctx ->
                                val previewView = PreviewView(ctx)
                                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                                cameraProviderFuture.addListener({
                                    val cameraProvider = cameraProviderFuture.get()
                                    val preview = Preview.Builder().build().also {
                                        it.surfaceProvider = previewView.surfaceProvider
                                    }
                                    val analyzer = ImageAnalysis.Builder()
                                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                        .build()
                                    analyzer.setAnalyzer(executor) { imageProxy ->
                                        if (!decodedOnce) {
                                            val plane = imageProxy.planes.firstOrNull()
                                            if (plane != null) {
                                                val buf = plane.buffer
                                                val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
                                                val decoded = QrPairingCodec.decodeQrFromYuvPlane(
                                                    yBytes = bytes,
                                                    width = imageProxy.width,
                                                    height = imageProxy.height
                                                )
                                                if (!decoded.isNullOrBlank()) {
                                                    decodedOnce = true
                                                    ContextCompat.getMainExecutor(ctx).execute {
                                                        onQrDecoded(decoded)
                                                    }
                                                }
                                            }
                                        }
                                        imageProxy.close()
                                    }
                                    runCatching {
                                        cameraProvider.unbindAll()
                                        cameraProvider.bindToLifecycle(
                                            lifecycleOwner,
                                            CameraSelector.DEFAULT_BACK_CAMERA,
                                            preview,
                                            analyzer
                                        )
                                    }
                                }, ContextCompat.getMainExecutor(ctx))
                                previewView
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Point camera at the other RescueMesh device's QR in Config.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = "Camera permission is needed to scan QR codes.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) {
                        Text("Grant Camera Permission")
                    }
                }
            }
        }
    }
}

/**
 * In-app CameraX photo capture modal for lowest-priority (P7) compressed scene photos on Sender Session Active.
 */
@Composable
fun CameraPhotoCaptureDialog(
    onPhotoCaptured: (ByteArray) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasCameraPerm by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPerm = granted }

    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var capturing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!hasCameraPerm) {
            launcher.launch(Manifest.permission.CAMERA)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .clip(RoundedCornerShape(24.dp)),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Capture Mesh Photo (P7)", style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "Close camera")
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                if (hasCameraPerm) {
                    val executor = remember { Executors.newSingleThreadExecutor() }
                    DisposableEffect(Unit) { onDispose { executor.shutdown() } }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp)
                            .clip(RoundedCornerShape(16.dp))
                    ) {
                        AndroidView(
                            factory = { ctx ->
                                val previewView = PreviewView(ctx)
                                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                                cameraProviderFuture.addListener({
                                    val cameraProvider = cameraProviderFuture.get()
                                    val preview = Preview.Builder().build().also {
                                        it.surfaceProvider = previewView.surfaceProvider
                                    }
                                    val capture = ImageCapture.Builder()
                                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                                        .build()
                                    imageCapture = capture
                                    runCatching {
                                        cameraProvider.unbindAll()
                                        cameraProvider.bindToLifecycle(
                                            lifecycleOwner,
                                            CameraSelector.DEFAULT_BACK_CAMERA,
                                            preview,
                                            capture
                                        )
                                    }
                                }, ContextCompat.getMainExecutor(ctx))
                                previewView
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        enabled = !capturing && imageCapture != null,
                        onClick = {
                            val cap = imageCapture ?: return@Button
                            capturing = true
                            cap.takePicture(
                                executor,
                                object : ImageCapture.OnImageCapturedCallback() {
                                    override fun onCaptureSuccess(image: ImageProxy) {
                                        val buf = image.planes[0].buffer
                                        val raw = ByteArray(buf.remaining()).also { buf.get(it) }
                                        image.close()
                                        val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size)
                                        val compressed = if (bmp != null) {
                                            QrPairingCodec.compressBitmapForMesh(bmp)
                                        } else {
                                            raw
                                        }
                                        ContextCompat.getMainExecutor(context).execute {
                                            capturing = false
                                            onPhotoCaptured(compressed)
                                        }
                                    }

                                    override fun onError(exception: ImageCaptureException) {
                                        ContextCompat.getMainExecutor(context).execute {
                                            capturing = false
                                        }
                                    }
                                }
                            )
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Icon(Icons.Filled.CameraAlt, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (capturing) "Compressing..." else "Capture & Queue Over Mesh")
                    }
                } else {
                    Text("Camera access needed to send an emergency photo.")
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) {
                        Text("Grant Camera Permission")
                    }
                }
            }
        }
    }
}
