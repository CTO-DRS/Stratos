package app.stratos.host.ui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// ---- Stratos 2035 visual effects -------------------------------------------
// All ambient motion here runs in the DRAW phase only (State reads inside
// drawBehind), so aurora drift and pulses never trigger recomposition.

/**
 * Living aurora backdrop: three plasma blobs (blue / violet / cyan) drifting
 * on a ~26s cycle behind the glass surfaces. Subtle by design.
 */
@Composable
fun AuroraBackdrop(modifier: Modifier = Modifier) {
    val dark = stratosIsDark()
    val transition = rememberInfiniteTransition(label = "aurora")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(26000, easing = LinearEasing)),
        label = "auroraPhase"
    )
    val strength = if (dark) 1f else 0.38f
    val blue = StratosColors.AuroraBlue.copy(alpha = 0.50f * strength)
    val violet = StratosColors.AuroraViolet.copy(alpha = 0.42f * strength)
    val cyan = StratosColors.AuroraCyan.copy(alpha = 0.40f * strength)
    val clear = Color.Transparent

    Box(
        modifier = modifier.drawBehind {
            val w = size.width
            val h = size.height
            val p = phase * 2f * PI.toFloat()

            fun blob(cx: Float, cy: Float, radius: Float, color: Color) {
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(color, clear),
                        center = Offset(cx, cy),
                        radius = radius
                    )
                )
            }

            // Blue — upper leading corner, breathes vertically.
            blob(
                cx = w * (0.18f + 0.07f * sin(p)),
                cy = h * (0.06f + 0.04f * cos(p * 0.8f)),
                radius = w * 0.85f,
                color = blue
            )
            // Violet — trailing mid, slow horizontal drift.
            blob(
                cx = w * (0.94f + 0.06f * cos(p * 1.15f)),
                cy = h * (0.34f + 0.05f * sin(p * 0.7f)),
                radius = w * 0.75f,
                color = violet
            )
            // Cyan — lower half, rises and sinks.
            blob(
                cx = w * (0.42f + 0.09f * sin(p * 0.9f + 1.2f)),
                cy = h * (0.98f + 0.05f * cos(p * 0.6f)),
                radius = w * 0.95f,
                color = cyan
            )
        }
    )
}

/** Glass surface: translucent fill + gradient hairline + soft top highlight. */
fun Modifier.stratosGlass(shape: Shape, highlight: Boolean = true): Modifier = composed {
    val dark = stratosIsDark()
    var m = this
        .background(glassFill(dark), shape)
        .border(1.dp, glassEdgeBrush(dark), shape)
    if (highlight) {
        m = m.drawBehind {
            // Inner top highlight — the "polished edge" of the glass.
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        if (dark) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.85f),
                        Color.Transparent
                    ),
                    startY = 0f,
                    endY = size.height * 0.35f
                ),
                size = androidx.compose.ui.geometry.Size(size.width, size.height * 0.35f)
            )
        }
    }
    m
}

/**
 * The signature CTA: energy-sweep gradient fill, breathing ion halo,
 * pressed feedback. All glow is drawn — no elevation shadows.
 */
@Composable
fun GlowButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    val dark = stratosIsDark()
    val shape = RoundedCornerShape(18.dp)
    val transition = rememberInfiniteTransition(label = "glow")
    val breathe by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            tween(2200, easing = LinearEasing),
            RepeatMode.Reverse
        ),
        label = "haloAlpha"
    )
    // Press micro-interaction: a quick springy squash, like a real key.
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.965f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 900f),
        label = "pressScale"
    )

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        if (enabled) {
            // Breathing halo behind the button.
            Box(
                Modifier
                    .matchParentSize()
                    .drawBehind {
                        drawRect(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    StratosColors.GlowB.copy(alpha = 0.34f * breathe),
                                    StratosColors.GlowA.copy(alpha = 0.16f * breathe),
                                    Color.Transparent
                                ),
                                center = Offset(size.width / 2f, size.height / 2f),
                                radius = size.maxDimension * 0.85f
                            )
                        )
                    }
            )
        }
        Row(
            modifier = Modifier
                .graphicsLayer {
                    alpha = if (enabled) 1f else 0.42f
                    scaleX = pressScale
                    scaleY = pressScale
                }
                .clip(shape)
                .background(stratosGlowBrush())
                .border(
                    1.dp,
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = if (enabled) 0.34f else 0.10f),
                            Color.White.copy(alpha = if (enabled) 0.06f else 0.03f)
                        )
                    ),
                    shape
                )
                .clickable(
                    enabled = enabled,
                    interactionSource = interaction,
                    indication = null,
                    onClick = onClick
                )
                .padding(horizontal = 22.dp, vertical = 14.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )
    }
}

/**
 * Floating gradient FAB with ion halo (Sites "import" action).
 */
@Composable
fun GlowFab(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "fab")
    val breathe by transition.animateFloat(
        initialValue = 0.5f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            tween(2400, easing = LinearEasing),
            RepeatMode.Reverse
        ),
        label = "fabHalo"
    )
    Box(modifier = modifier.size(58.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(58.dp)
                .drawBehind {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                StratosColors.GlowB.copy(alpha = 0.38f * breathe),
                                Color.Transparent
                            ),
                            center = Offset(size.width / 2f, size.height / 2f),
                            radius = size.width / 2f
                        )
                    )
                }
        )
        Box(
            modifier = Modifier
                .size(54.dp)
                .clip(CircleShape)
                .background(stratosGlowBrush())
                .border(
                    1.dp,
                    Brush.linearGradient(
                        listOf(Color.White.copy(alpha = 0.45f), Color.White.copy(alpha = 0.10f))
                    ),
                    CircleShape
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription, tint = Color.White, modifier = Modifier.size(26.dp))
        }
    }
}

/** Gradient logo tile ("▲" mark) for app headers. */
@Composable
fun LogoTile(modifier: Modifier = Modifier, glyph: String = "▲", sizeDp: Int = 28) {
    val shape = RoundedCornerShape((sizeDp * 0.30f).dp)
    Box(
        modifier
            .size(sizeDp.dp)
            .clip(shape)
            .background(stratosGlowBrush())
            .border(
                1.dp,
                Brush.linearGradient(
                    listOf(Color.White.copy(alpha = 0.5f), Color.White.copy(alpha = 0.12f))
                ),
                shape
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            glyph,
            color = Color.White,
            fontSize = (sizeDp * 0.48f).sp,
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * Animated "living" status dot: glowing core + breathing plasma halo.
 * Draw-phase animation only.
 */
@Composable
fun PlasmaDot(color: Color, modifier: Modifier = Modifier, pulse: Boolean = true) {
    val transition = rememberInfiniteTransition(label = "dot")
    val breathe by transition.animateFloat(
        initialValue = 0.0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(if (pulse) 1400 else 4000, easing = LinearEasing),
            RepeatMode.Reverse
        ),
        label = "dotAlpha"
    )
    Box(modifier.size(18.dp), contentAlignment = Alignment.Center) {
        // Halo
        Box(
            Modifier
                .size(18.dp)
                .drawBehind {
                    drawCircle(
                        color = color.copy(alpha = 0.16f + 0.18f * breathe),
                        radius = size.width / 2f
                    )
                }
        )
        // Core
        Box(
            Modifier
                .size(8.dp)
                .drawBehind {
                    drawCircle(color = color.copy(alpha = 0.72f + 0.28f * breathe))
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.55f * breathe),
                                Color.Transparent
                            )
                        )
                    )
                }
        )
    }
}
