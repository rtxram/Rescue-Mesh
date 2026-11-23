package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

data class RescueSemanticColors(
    val sosCrimson: Color = SosCrimson,
    val sosCrimsonBright: Color = SosCrimsonBright,
    val sosCrimsonGlow: Color = SosCrimsonGlow,
    val emeraldActive: Color = EmeraldActive,
    val emeraldContainer: Color = EmeraldContainerSoft,
    val mintTick: Color = MintReadTick,
    val forestContainer: Color = TertiaryContainerForest,
    val amberRelay: Color = RelayAmber,
    val amberRelayContainer: Color = RelayAmberContainer,
    val primaryNavy: Color = PrimaryContainerNavy,
    val onPrimarySlate: Color = OnPrimaryContainerSlate,
    val cardWhite: Color = SurfaceCardWhite,
    val hairlineBorder: Color = OutlineSubtle
)

val LocalRescueColors = staticCompositionLocalOf { RescueSemanticColors() }

private val LightRescueColorScheme = lightColorScheme(
    primary = PrimaryBlack,
    onPrimary = SurfaceCardWhite,
    primaryContainer = PrimaryContainerNavy,
    onPrimaryContainer = OnPrimaryContainerSlate,
    secondary = SosCrimson,
    onSecondary = SurfaceCardWhite,
    secondaryContainer = SosCrimsonBright,
    onSecondaryContainer = SurfaceCardWhite,
    tertiary = EmeraldActive,
    onTertiary = SurfaceCardWhite,
    tertiaryContainer = TertiaryContainerForest,
    onTertiaryContainer = EmeraldActive,
    background = SurfaceCanvas,
    onBackground = OnSurfaceInk,
    surface = SurfaceCanvas,
    onSurface = OnSurfaceInk,
    surfaceVariant = SurfaceContainerHighest,
    onSurfaceVariant = OnSurfaceMuted,
    surfaceContainerLowest = SurfaceCardWhite,
    surfaceContainerLow = SurfaceContainerLow,
    surfaceContainer = SurfaceContainer,
    surfaceContainerHigh = SurfaceContainerHigh,
    surfaceContainerHighest = SurfaceContainerHighest,
    outline = OutlineMuted,
    outlineVariant = OutlineSubtle,
    error = SosCrimson,
    onError = SurfaceCardWhite,
    errorContainer = ErrorContainerSoft,
    onErrorContainer = OnErrorContainerDeep
)

private val RescueShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun RescueMeshTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalRescueColors provides RescueSemanticColors()) {
        MaterialTheme(
            colorScheme = LightRescueColorScheme,
            typography = RescueMeshTypography,
            shapes = RescueShapes,
            content = content
        )
    }
}
