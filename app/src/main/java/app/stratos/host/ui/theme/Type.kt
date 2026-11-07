package app.stratos.host.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.stratos.host.R

// ---- Stratos type system ----------------------------------------------------
// Two bundled families, both SIL Open Font License 1.1:
//
//  • Readex Pro (400/500/600/700) — the UI voice. An Arabic-first geometric
//    sans (Lexend lineage) with complete Arabic, Turkish (Ğ ğ İ ş) and Latin
//    coverage, so all three UI languages speak with one coherent voice.
//
//  • Space Grotesk (500/700) — the display voice for strictly-Latin strings:
//    stat numerals, URLs, ports and the STRATOS wordmark. Its technical,
//    slightly squared letterforms carry the 2035 instrument-panel accent.
//
// Arabic script is cursive: every character joins the next. Any nonzero
// letter-spacing is inserted between the shaped clusters and visibly breaks
// those joins, so the shared typography below is intentionally tracking-free.
// Wide tracking is reserved for Latin-only brand strings via
// StratosWordmarkSpacing — applied locally at the call site, never here.

/** The Stratos UI family (Readex Pro), weight-mapped for every FontWeight. */
val StratosFontFamily = FontFamily(
    Font(R.font.readexpro_regular, FontWeight.Normal),
    Font(R.font.readexpro_medium, FontWeight.Medium),
    Font(R.font.readexpro_semibold, FontWeight.SemiBold),
    Font(R.font.readexpro_bold, FontWeight.Bold),
)

/**
 * Latin-only display family (Space Grotesk). NEVER apply it to an Arabic or
 * Turkish string — it has no Arabic glyphs and Turkish would fall back mid-word.
 */
val StratosDisplayFamily = FontFamily(
    Font(R.font.spacegrotesk_medium, FontWeight.Medium),
    Font(R.font.spacegrotesk_bold, FontWeight.Bold),
)

/** Wordmark tracking multiplier — ONLY for Latin-only strings (see rules above). */
val StratosWordmarkSpacing = 0.18.sp

/** One Stratos text style: UI family, generous line height, zero tracking. */
private fun stratos(fontSize: Float, fontWeight: FontWeight, lineHeight: Float) = TextStyle(
    fontFamily = StratosFontFamily,
    fontSize = fontSize.sp,
    fontWeight = fontWeight,
    lineHeight = lineHeight.sp,
    letterSpacing = 0.sp
)

/** Latin-only display style: Space Grotesk, zero tracking by default. */
fun stratosDisplay(fontSize: Float, fontWeight: FontWeight, lineHeight: Float) = TextStyle(
    fontFamily = StratosDisplayFamily,
    fontSize = fontSize.sp,
    fontWeight = fontWeight,
    lineHeight = lineHeight.sp,
    letterSpacing = 0.sp
)

/**
 * Material 3 typography wired to [StratosFontFamily] (Readex Pro). Sizes and
 * weights match the Stratos scale; tracking is zeroed for Arabic safety and
 * line heights stay generous (Arabic needs vertical room for dots and hamza).
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
