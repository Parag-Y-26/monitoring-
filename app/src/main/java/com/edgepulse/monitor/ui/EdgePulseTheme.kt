package com.edgepulse.monitor.ui

import android.app.ActivityManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object EdgePalette {
    val Paper = Color(0xFFF3F3F3)
    val Silver = Color(0xFFBCBCBE)
    val Ink = Color(0xFF060606)
    val Graphite = Color(0xFF4B4C51)
    val Mist = Color(0xFFD0D0D2)
}

object EdgeMotion {
    const val FastMs = 160
    const val NormalMs = 220
    const val ExitMs = 120
    const val ActivityMs = 900
}

data class MotionPolicy(val enabled: Boolean = true) {
    fun duration(value: Int) = if (enabled) value else 0
}
val LocalEdgeMotion = staticCompositionLocalOf { MotionPolicy() }

private val LightColors = lightColorScheme(
    primary = EdgePalette.Ink, onPrimary = EdgePalette.Paper,
    primaryContainer = EdgePalette.Mist, onPrimaryContainer = EdgePalette.Ink,
    secondary = EdgePalette.Graphite, onSecondary = EdgePalette.Paper,
    secondaryContainer = EdgePalette.Mist, onSecondaryContainer = EdgePalette.Ink,
    tertiary = EdgePalette.Graphite, onTertiary = EdgePalette.Paper,
    tertiaryContainer = EdgePalette.Mist, onTertiaryContainer = EdgePalette.Ink,
    background = EdgePalette.Paper, onBackground = EdgePalette.Ink,
    surface = EdgePalette.Paper, onSurface = EdgePalette.Ink,
    surfaceVariant = EdgePalette.Mist, onSurfaceVariant = EdgePalette.Graphite,
    surfaceDim = EdgePalette.Mist, surfaceBright = EdgePalette.Paper,
    surfaceContainerLowest = EdgePalette.Paper, surfaceContainerLow = EdgePalette.Paper,
    surfaceContainer = EdgePalette.Paper, surfaceContainerHigh = EdgePalette.Mist, surfaceContainerHighest = EdgePalette.Mist,
    outline = EdgePalette.Graphite, outlineVariant = EdgePalette.Silver,
    inverseSurface = EdgePalette.Ink, inverseOnSurface = EdgePalette.Paper, inversePrimary = EdgePalette.Paper,
    error = EdgePalette.Ink, onError = EdgePalette.Paper, errorContainer = EdgePalette.Mist, onErrorContainer = EdgePalette.Ink,
    scrim = EdgePalette.Ink, surfaceTint = Color.Transparent,
)
private val DarkColors = darkColorScheme(
    primary = EdgePalette.Paper, onPrimary = EdgePalette.Ink,
    primaryContainer = EdgePalette.Graphite, onPrimaryContainer = EdgePalette.Paper,
    secondary = EdgePalette.Mist, onSecondary = EdgePalette.Ink,
    secondaryContainer = EdgePalette.Graphite, onSecondaryContainer = EdgePalette.Paper,
    tertiary = EdgePalette.Silver, onTertiary = EdgePalette.Ink,
    tertiaryContainer = EdgePalette.Graphite, onTertiaryContainer = EdgePalette.Paper,
    background = EdgePalette.Ink, onBackground = EdgePalette.Paper,
    surface = EdgePalette.Ink, onSurface = EdgePalette.Paper,
    surfaceVariant = EdgePalette.Graphite, onSurfaceVariant = EdgePalette.Silver,
    surfaceDim = EdgePalette.Ink, surfaceBright = EdgePalette.Graphite,
    surfaceContainerLowest = EdgePalette.Ink, surfaceContainerLow = EdgePalette.Ink,
    surfaceContainer = EdgePalette.Ink, surfaceContainerHigh = EdgePalette.Graphite, surfaceContainerHighest = EdgePalette.Graphite,
    outline = EdgePalette.Silver, outlineVariant = EdgePalette.Graphite,
    inverseSurface = EdgePalette.Paper, inverseOnSurface = EdgePalette.Ink, inversePrimary = EdgePalette.Ink,
    error = EdgePalette.Paper, onError = EdgePalette.Ink, errorContainer = EdgePalette.Graphite, onErrorContainer = EdgePalette.Paper,
    scrim = EdgePalette.Ink, surfaceTint = Color.Transparent,
)

private val EdgeTypography = Typography(
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.6).sp),
    headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 23.sp, lineHeight = 29.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 21.sp, lineHeight = 27.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 10.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp),
)

@Composable
fun EdgePulseTheme(darkTheme: Boolean = isSystemInDarkTheme(), reducedMotion: Boolean? = null, content: @Composable () -> Unit) {
    val context = LocalContext.current
    var systemMotion by remember { mutableStateOf(Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f) }
    val lowRam = remember { context.getSystemService(ActivityManager::class.java).isLowRamDevice }
    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                systemMotion = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
            }
        }
        context.contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    CompositionLocalProvider(LocalEdgeMotion provides MotionPolicy(!(reducedMotion ?: !systemMotion) && !lowRam)) {
        MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors, typography = EdgeTypography,
            shapes = Shapes(small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(22.dp)), content = content)
    }
}
