package app.stratos.host.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.composed
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.stratos.host.ui.theme.PlasmaDot
import app.stratos.host.ui.theme.StratosColors
import app.stratos.host.ui.theme.StratosDisplayFamily
import app.stratos.host.ui.theme.glassEdgeBrush
import app.stratos.host.ui.theme.glassFill
import app.stratos.host.ui.theme.stratosGlowBrush
import app.stratos.host.ui.theme.stratosIsDark

/** Living status dot: glowing core + breathing plasma halo. */
@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    PlasmaDot(color, modifier)
}

/** Glass pill badge ("RUNNING", "GO ENGINE"…). */
@Composable
fun Pill(
    text: String,
    container: Color = MaterialTheme.colorScheme.surfaceVariant,
    content: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier: Modifier = Modifier
) {
    val dark = stratosIsDark()
    val fill = if (container == MaterialTheme.colorScheme.surfaceVariant) {
        glassFill(dark)
    } else {
        container.copy(alpha = if (dark) 0.22f else 0.55f)
    }
    Box(
        modifier = modifier
            .background(fill, RoundedCornerShape(999.dp))
            .border(
                1.dp,
                Brush.linearGradient(
                    listOf(
                        content.copy(alpha = if (dark) 0.34f else 0.22f),
                        Color.Transparent
                    )
                ),
                RoundedCornerShape(999.dp)
            )
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = content)
    }
}

/**
 * Glass stat tile, instrument-panel style: Space Grotesk numerals with an
 * animated value transition and an optional glowing icon chip.
 */
@Composable
fun StatCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    icon: ImageVector? = null
) {
    val dark = stratosIsDark()
    val shape = RoundedCornerShape(18.dp)
    val chipShape = RoundedCornerShape(11.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(glassFill(dark), shape)
            .border(1.dp, glassEdgeBrush(dark), shape)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            AnimatedContent(
                targetState = value,
                transitionSpec = {
                    (slideInVertically(tween(280)) { it / 3 } + fadeIn(tween(280))) togetherWith
                        (slideOutVertically(tween(280)) { -it / 3 } + fadeOut(tween(180)))
                },
                label = "statValue"
            ) { v ->
                Text(
                    v,
                    color = valueColor,
                    fontFamily = StratosDisplayFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 23.sp,
                    letterSpacing = 0.sp,
                    maxLines = 1
                )
            }
        }
        icon?.let { ic ->
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier
                    .size(34.dp)
                    .clip(chipShape)
                    .background(
                        if (dark) Color.White.copy(alpha = 0.06f)
                        else StratosColors.AccentSoft.copy(alpha = 0.75f),
                        chipShape
                    )
                    .border(
                        1.dp,
                        Brush.linearGradient(
                            listOf(
                                StratosColors.GlowA.copy(alpha = if (dark) 0.38f else 0.26f),
                                StratosColors.GlowB.copy(alpha = 0.06f)
                            )
                        ),
                        chipShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    ic,
                    contentDescription = null,
                    tint = if (dark) StratosColors.DarkAccent else StratosColors.Accent,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/** Glass section panel with an optional header row. */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    header: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(glassFill(stratosIsDark()), shape)
            .border(1.dp, glassEdgeBrush(stratosIsDark()), shape)
    ) {
        if (header != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) { header() }
            Row(
                Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                        RoundedCornerShape(1.dp)
                    )
            ) {}
        }
        content()
    }
}

/** URL / identifier text in the Latin display family (input is always ASCII). */
@Composable
fun MonoText(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface) {
    Text(
        text,
        modifier = modifier,
        fontFamily = StratosDisplayFamily,
        fontSize = 13.sp,
        color = color,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.sp
    )
}

/** Clickable without the ripple splash (used for swatches and rows). */
fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = composed {
    this.clickable(
        interactionSource = androidx.compose.foundation.interaction.MutableInteractionSource(),
        indication = null,
        onClick = onClick
    )
}
