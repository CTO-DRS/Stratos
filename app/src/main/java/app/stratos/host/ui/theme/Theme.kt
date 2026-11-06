package app.stratos.host.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---- Vercel-inspired palette (light-first) ---------------------------------

object StratosColors {
    // Light
    val LightBg = Color(0xFFFFFFFF)
    val LightSurface = Color(0xFFFAFAFA)
    val LightSurfaceVariant = Color(0xFFF4F4F5)
    val LightOutline = Color(0xFFE4E4E7)
    val LightOutlineStrong = Color(0xFFCFCFD4)
    val LightText = Color(0xFF0A0A0A)
    val LightMuted = Color(0xFF71717A)
    val Accent = Color(0xFF0A7CFF)
    val AccentSoft = Color(0xFFEAF4FF)
    val Success = Color(0xFF16A34A)
    val SuccessSoft = Color(0xFFE9F9EF)
    val Warning = Color(0xFFD97706)
    val WarningSoft = Color(0xFFFFF4E5)
    val Error = Color(0xFFDC2626)
    val ErrorSoft = Color(0xFFFDECEC)

    // Dark
    val DarkBg = Color(0xFF0A0A0A)
    val DarkSurface = Color(0xFF111111)
    val DarkSurfaceVariant = Color(0xFF171717)
    val DarkOutline = Color(0xFF27272A)
    val DarkText = Color(0xFFFAFAFA)
    val DarkMuted = Color(0xFF9F9FA8)
    val DarkAccent = Color(0xFF3B9EFF)
}

private val LightScheme = lightColorScheme(
    primary = Color(0xFF0A0A0A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF171717),
    onPrimaryContainer = Color.White,
    secondary = StratosColors.Accent,
    onSecondary = Color.White,
    secondaryContainer = StratosColors.AccentSoft,
    onSecondaryContainer = Color(0xFF084B8F),
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
    primary = Color(0xFFFAFAFA),
    onPrimary = Color(0xFF0A0A0A),
    primaryContainer = Color(0xFF27272A),
    onPrimaryContainer = Color(0xFFFAFAFA),
    secondary = StratosColors.DarkAccent,
    onSecondary = Color(0xFF06213D),
    secondaryContainer = Color(0xFF0D2A4A),
    onSecondaryContainer = Color(0xFFBFE0FF),
    tertiary = Color(0xFF4ADE80),
    background = StratosColors.DarkBg,
    onBackground = StratosColors.DarkText,
    surface = StratosColors.DarkSurface,
    onSurface = StratosColors.DarkText,
    surfaceVariant = StratosColors.DarkSurfaceVariant,
    onSurfaceVariant = StratosColors.DarkMuted,
    outline = StratosColors.DarkOutline,
    outlineVariant = Color(0xFF1F1F23),
    error = Color(0xFFF87171),
    errorContainer = Color(0xFF3B0D0D),
    onError = Color(0xFF0A0A0A),
    onErrorContainer = Color(0xFFFECACA)
)

private val StratosShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(22.dp)
)

private val StratosTypography = Typography(
    headlineLarge = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
    headlineMedium = TextStyle(fontSize = 25.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
    headlineSmall = TextStyle(fontSize = 21.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleLarge = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.5.sp, fontWeight = FontWeight.Normal, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.Normal, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp)
)

/** Light-first theme (default), with an automatic dark companion. */
@Composable
fun StratosTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        shapes = StratosShapes,
        typography = StratosTypography,
        content = content
    )
}
