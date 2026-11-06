package app.stratos.host.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.stratos.host.R

// ---- Stratos type system ----------------------------------------------------
// Brand family: Cairo (SIL Open Font License 1.1) — a geometric, modern,
// Arabic-first family that also covers Latin and Turkish (ğ/İ/ş/ç included),
// so all three UI languages speak with one coherent voice.
//
// Arabic script is cursive: every character joins the next. Any nonzero
// letter-spacing is inserted between the shaped clusters and visibly breaks
// those joins, so the shared typography below is intentionally tracking-free.
// Wide tracking is reserved for Latin-only brand strings (e.g. the STRATOS
// wordmark) and applied locally at the call site — never here.

/** The Stratos brand family, weight-mapped for every Compose FontWeight. */
val StratosFontFamily = FontFamily(
    Font(R.font.cairo_regular, FontWeight.Normal),
    Font(R.font.cairo_medium, FontWeight.Medium),
    Font(R.font.cairo_semibold, FontWeight.SemiBold),
    Font(R.font.cairo_bold, FontWeight.Bold),
)

/** Wordmark tracking multiplier — ONLY for Latin-only strings (see rules above). */
val StratosWordmarkSpacing = 0.18.sp

/** One Stratos text style: brand family, generous line height, zero tracking. */
private fun stratos(fontSize: Float, fontWeight: FontWeight, lineHeight: Float) = TextStyle(
    fontFamily = StratosFontFamily,
    fontSize = fontSize.sp,
    fontWeight = fontWeight,
    lineHeight = lineHeight.sp,
    letterSpacing = 0.sp
)

/**
 * Material 3 typography wired to [StratosFontFamily]. Sizes and weights match
 * the original Stratos scale; tracking is zeroed for Arabic safety and line
 * heights are kept generous (Arabic needs vertical room for dots and hamza).
 */
val StratosTypography = Typography(
    headlineLarge = stratos(30f, FontWeight.Bold, 40f),
    headlineMedium = stratos(25f, FontWeight.Bold, 34f),
    headlineSmall = stratos(21f, FontWeight.SemiBold, 29f),
    titleLarge = stratos(18f, FontWeight.SemiBold, 25f),
    titleMedium = stratos(15.5f, FontWeight.SemiBold, 22f),
    titleSmall = stratos(14f, FontWeight.SemiBold, 20f),
    bodyLarge = stratos(15.5f, FontWeight.Normal, 25f),
    bodyMedium = stratos(14f, FontWeight.Normal, 22f),
    bodySmall = stratos(12.5f, FontWeight.Normal, 19f),
    labelLarge = stratos(14f, FontWeight.SemiBold, 20f),
    labelMedium = stratos(12f, FontWeight.Medium, 16f),
    labelSmall = stratos(11f, FontWeight.Medium, 15f)
)
