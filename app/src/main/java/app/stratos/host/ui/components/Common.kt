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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.composed
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.stratos.host.ui.theme.PlasmaDot
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

/** Glass stat tile with an animated value transition and energy tick. */
@Composable
fun StatCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    mono: Boolean = true
) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(glassFill(stratosIsDark()), shape)
            .border(1.dp, glassEdgeBrush(stratosIsDark()), shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        // Energy tick — a small signature gradient under the label.
        Box(
            Modifier
                .width(26.dp)
                .height(3.dp)
                .background(stratosGlowBrush(), RoundedCornerShape(2.dp))
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
                style = MaterialTheme.typography.titleLarge,
                color = valueColor,
                fontFamily = if (mono) FontFamily.Monospace else null,
                maxLines = 1
            )
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

/** Monospace URL row with the given content. */
@Composable
fun MonoText(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface) {
    Text(
        text,
        modifier = modifier,
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        color = color,
        fontWeight = FontWeight.Medium
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
