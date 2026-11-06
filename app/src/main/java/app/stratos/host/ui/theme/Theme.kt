package app.stratos.host.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// ---- Stratos 2035 palette --------------------------------------------------
// Deep-space glass + aurora. Surfaces are translucent by design; the aurora
// backdrop (see Effects.kt) breathes behind the glass so the UI feels alive.

object StratosColors {
    // Light ("glacier") — cold ice-white with ambient tint channels.
    val LightBg = Color(0xFFF6F8FF)
    val LightSurface = Color(0xFFFDFFFF)
    val LightSurfaceVariant = Color(0xFFEEF2FC)
    val LightOutline = Color(0xFFDCE3F4)
    val LightOutlineStrong = Color(0xFFC6CFE6)
    val LightText = Color(0xFF0B1020)
    val LightMuted = Color(0xFF5D657E)
    val Accent = Color(0xFF0B6BFF)
    val AccentSoft = Color(0xFFE8F1FF)
    val Success = Color(0xFF0FA968)
    val SuccessSoft = Color(0xFFE5F8EF)
    val Warning = Color(0xFFC77400)
    val WarningSoft = Color(0xFFFFF3E0)
    val Error = Color(0xFFDC2626)
    val ErrorSoft = Color(0xFFFDECEC)

    // Dark ("deep space") — near-black blue, aurora-ready.
    val DarkBg = Color(0xFF05060C)
    val DarkSurface = Color(0xFF0B0E18)
    val DarkSurfaceVariant = Color(0xFF131828)
    val DarkOutline = Color(0xFF252D44)
    val DarkText = Color(0xFFF2F5FF)
    val DarkMuted = Color(0xFF8C93AD)
    val DarkAccent = Color(0xFF5EA2FF)

    // Glow gradient stops — the signature "2035" energy sweep.
    val GlowA = Color(0xFF3D7BFF) // electric blue
    val GlowB = Color(0xFF7A5CFF) // ion violet
    val GlowC = Color(0xFF23C4E6) // plasma cyan

    // Aurora blob colors (backdrop ambience).
    val AuroraBlue = Color(0xFF1B3B8F)
    val AuroraViolet = Color(0xFF4C1D95)
    val AuroraCyan = Color(0xFF0E5C74)
}

/** The signature gradient sweep, used for CTAs, indicators and hairlines. */
fun stratosGlowBrush(): Brush = Brush.linearGradient(
    colors = listOf(StratosColors.GlowA, StratosColors.GlowB, StratosColors.GlowC)
)

/** Hairline gradient for glass edges: bright top-left, fading bottom-right. */
fun glassEdgeBrush(dark: Boolean): Brush = if (dark) {
    Brush.linearGradient(
        colors = listOf(
            Color.White.copy(alpha = 0.20f),
            Color.White.copy(alpha = 0.05f),
            Color.White.copy(alpha = 0.02f)
        )
    )
} else {
    Brush.linearGradient(
        colors = listOf(
            Color(0xFF0B1020).copy(alpha = 0.14f),
            Color(0xFF0B1020).copy(alpha = 0.05f),
            Color(0xFF0B1020).copy(alpha = 0.03f)
        )
    )
}

/** Glass fill for panels: translucent so the aurora glows through. */
fun glassFill(dark: Boolean): Color =
    if (dark) Color.White.copy(alpha = 0.045f)
    else Color.White.copy(alpha = 0.62f)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF0B1020),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF111A33),
    onPrimaryContainer = Color.White,
    secondary = StratosColors.Accent,
    onSecondary = Color.White,
    secondaryContainer = StratosColors.AccentSoft,
    onSecondaryContainer = Color(0xFF073E85),
    tertiary = StratosColors.Success,
    background = StratosColors.LightBg,
    onBackground = StratosColors.LightText,
    surface = StratosColors.LightSurface,
    onSurface = StratosColors.LightText,
    surfaceVariant = StratosColors.LightSurfaceVariant,
    onSurfaceVariant = StratosColors.LightMuted,
    outline = StratosColors.LightOutline,
    outlineVariant = StratosColors.LightSurfaceVariant,
    error = StratosColors.Error,
    errorContainer = StratosColors.ErrorSoft,
    onError = Color.White,
    onErrorContainer = Color(0xFF7F1D1D)
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFF2F5FF),
    onPrimary = Color(0xFF05060C),
    primaryContainer = Color(0xFF252D44),
    onPrimaryContainer = Color(0xFFF2F5FF),
    secondary = StratosColors.DarkAccent,
    onSecondary = Color(0xFF041830),
    secondaryContainer = Color(0xFF0D2A4A),
    onSecondaryContainer = Color(0xFFBFE0FF),
    tertiary = Color(0xFF34D399),
    background = StratosColors.DarkBg,
    onBackground = StratosColors.DarkText,
    surface = StratosColors.DarkSurface,
    onSurface = StratosColors.DarkText,
    surfaceVariant = StratosColors.DarkSurfaceVariant,
    onSurfaceVariant = StratosColors.DarkMuted,
    outline = StratosColors.DarkOutline,
    outlineVariant = Color(0xFF1A2136),
    error = Color(0xFFF87171),
    errorContainer = Color(0xFF3B0D0D),
    onError = Color(0xFF0A0A0A),
    onErrorContainer = Color(0xFFFECACA)
)

private val StratosShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

// Typography (StratosFontFamily = bundled Cairo, Arabic-safe zero tracking)
// lives in Type.kt — same package, wired into StratosTheme below.

/** Tracks the active Stratos darkness for glass/aurora helpers. */
val LocalStratosDark = staticCompositionLocalOf { false }

@Composable
fun stratosIsDark(): Boolean = LocalStratosDark.current

/** 2035 theme: glass surfaces over a living aurora, light-first default. */
@Composable
fun StratosTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        shapes = StratosShapes,
        typography = StratosTypography
    ) {
        CompositionLocalProvider(LocalStratosDark provides darkTheme) {
            content()
        }
    }
}
