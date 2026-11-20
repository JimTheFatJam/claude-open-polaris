package io.github.jimthefatjam.polaris.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val scheme = darkColorScheme(
    primary = PolarisColors.Accent,
    onPrimary = PolarisColors.Night,
    primaryContainer = PolarisColors.SurfaceHigh,
    onPrimaryContainer = PolarisColors.Starlight,
    secondary = PolarisColors.AccentWarm,
    onSecondary = PolarisColors.Night,
    secondaryContainer = PolarisColors.SurfaceHigh,
    onSecondaryContainer = PolarisColors.Starlight,
    background = PolarisColors.Night,
    onBackground = PolarisColors.Starlight,
    surface = PolarisColors.NightElevated,
    onSurface = PolarisColors.Starlight,
    surfaceVariant = PolarisColors.Surface,
    onSurfaceVariant = PolarisColors.Muted,
    surfaceContainer = PolarisColors.NightElevated,
    surfaceContainerHigh = PolarisColors.Surface,
    surfaceContainerHighest = PolarisColors.SurfaceHigh,
    surfaceContainerLow = PolarisColors.NightElevated,
    outline = PolarisColors.Outline,
    outlineVariant = PolarisColors.Outline,
    error = PolarisColors.Unsuitable,
    onError = PolarisColors.Night,
)

private val base = Typography()
private val typography = Typography(
    displayLarge = base.displayLarge.copy(fontWeight = FontWeight.Light, letterSpacing = (-1).sp),
    displayMedium = base.displayMedium.copy(fontWeight = FontWeight.Light),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Medium),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Medium),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Medium),
    labelSmall = base.labelSmall.copy(letterSpacing = 0.8.sp),
)

val MonoNumbers = TextStyle(fontFamily = FontFamily.Monospace)

@Composable
fun PolarisTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
