package app.stratos.host.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOutBack
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import app.stratos.host.BuildConfig
import app.stratos.host.R
import app.stratos.host.ui.components.Pill
import app.stratos.host.ui.theme.AuroraBackdrop
import app.stratos.host.ui.theme.GlowButton
import app.stratos.host.ui.theme.LogoTile
import app.stratos.host.ui.theme.StratosColors
import app.stratos.host.ui.theme.StratosFontFamily
import app.stratos.host.ui.theme.glassEdgeBrush
import app.stratos.host.ui.theme.glassFill
import app.stratos.host.ui.theme.stratosGlowBrush
import app.stratos.host.ui.theme.stratosGlass
import app.stratos.host.ui.theme.stratosIsDark
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.Manifest
import android.content.pm.PackageManager
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

// ---------------------------------------------------------------------------
// Launch experience: splash → welcome → app tour → permissions → main app.
// Every surface is trilingual (AR/EN/TR) and RTL-safe; the aurora + glass
// language is the same as the rest of the 2035 interface.
//
// Animation rules (the "extra motion" layer):
//  · Ambient loops (rings, orbits, breathing) read state inside drawBehind /
//    graphicsLayer lambdas only → draw-phase motion, zero recomposition.
//  · Entrances are choreographed (staggered windows on one shared progress),
//    so a page arrives as a sequence, not a single fade.
//  · Direction-sensitive motion is avoided; vertical slides, scale and fade
//    are identical in RTL and LTR, and pager parallax lives in page-local
//    coordinates (mirrored automatically with the layout direction).
// ---------------------------------------------------------------------------

/** Reactive launch gate used by MainActivity (see [SplashScreen] / [OnboardingScreen]). */
@Composable
fun StratosLaunchGate(
    onboardingDone: Boolean?,
    onFinishOnboarding: () -> Unit,
    app: @Composable () -> Unit
) {
    // Guarantee a minimum splash display so the brand moment is never a flash,
    // even when DataStore resolves in a few milliseconds.
    var minSplashDone by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(1600)
        minSplashDone = true
    }
    when {
        onboardingDone == null || !minSplashDone -> SplashScreen()
        !onboardingDone -> OnboardingScreen(onFinish = onFinishOnboarding)
        else -> app()
    }
}

// ---- Shared choreography helpers --------------------------------------------

/** Maps a shared progress [p] into the [start,end] window, clamped to 0..1. */
private fun window(p: Float, start: Float, end: Float): Float =
    ((p - start) / (end - start)).coerceIn(0f, 1f)

/**
 * One-shot entrance: fades and lifts its content after a per-index delay.
 * Used to choreograph a stack of elements (welcome text, permission cards).
 */
@Composable
private fun StaggerIn(index: Int, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(140L + index * 95L)
        p.animateTo(1f, tween(560, easing = FastOutSlowInEasing))
    }
    Box(
        modifier.graphicsLayer {
            alpha = p.value
            translationY = (1f - p.value) * 34f
        }
    ) { content() }
}

/**
 * The living aura stage: rotating sweep ring (optional), breathing radial
 * halo, and three ion particles orbiting on an ellipse with comet tails.
 * All motion reads animation state inside drawBehind / graphicsLayer lambdas
 * — draw-phase only, no recomposition per frame.
 */
@Composable
private fun AuraStage(
    sizeDp: Int,
    ring: Boolean,
    haloColor: Color,
    haloAlpha: Float,
    breatheContent: Boolean = false,
    content: @Composable () -> Unit
) {
    val t = rememberInfiniteTransition(label = "aura")
    val rot by t.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(5200, easing = LinearEasing)),
        label = "rot"
    )
    val orbit by t.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(9000, easing = LinearEasing)),
        label = "orbit"
    )
    val breathe by t.animateFloat(
        initialValue = 0.4f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Reverse),
        label = "breathe"
    )

    Box(Modifier.size(sizeDp.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .matchParentSize()
                .drawBehind {
                    val r = size.minDimension / 2f
                    if (ring) {
                        rotate(rot) {
                            drawCircle(
                                brush = Brush.sweepGradient(
                                    0f to Color.Transparent,
                                    0.30f to StratosColors.GlowA.copy(alpha = 0.9f),
                                    0.55f to StratosColors.GlowB.copy(alpha = 0.75f),
                                    0.80f to StratosColors.GlowC.copy(alpha = 0.9f),
                                    1f to Color.Transparent
                                ),
                                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                            )
                        }
                    }
                    // Breathing halo under everything.
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                haloColor.copy(alpha = haloAlpha * breathe),
                                haloColor.copy(alpha = haloAlpha * 0.45f * breathe),
                                Color.Transparent
                            ),
                            radius = r
                        )
                    )
                    // Three orbiting ions with comet tails (elliptical orbit).
                    val orbR = r * 0.94f
                    val ions = listOf(StratosColors.GlowA, StratosColors.GlowB, StratosColors.GlowC)
                    repeat(3) { i ->
                        val a0 = (orbit + i / 3f) * 2f * PI.toFloat()
                        repeat(4) { tail ->
                            val a = a0 - tail * 0.055f
                            drawCircle(
                                color = ions[i].copy(alpha = (0.95f - tail * 0.26f).coerceIn(0f, 1f)),
                                radius = ((3.1f - tail * 0.7f).coerceAtLeast(0.6f)).dp.toPx(),
                                center = Offset(
                                    center.x + cos(a) * orbR,
                                    center.y + sin(a) * orbR * 0.34f
                                )
                            )
                        }
                    }
                }
        )
        Box(
            Modifier.graphicsLayer {
                if (breatheContent) {
                    val s = 0.968f + 0.032f * breathe
                    scaleX = s
                    scaleY = s
                    translationY = (breathe - 0.7f) * 2f * 4.dp.toPx()
                }
            },
            contentAlignment = Alignment.Center
        ) { content() }
    }
}

// ---- 1 · Splash (شاشة البداية) ----------------------------------------------

@Composable
fun SplashScreen() {
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        appear.animateTo(1f, tween(950, easing = FastOutSlowInEasing))
    }
    // Wordmark tracking settles from wide to final — a small "boot" moment.
    // Latin-only string, so letter-spacing is script-safe here.
    val tracking = remember { Animatable(2.6f) }
    LaunchedEffect(Unit) {
        delay(140)
        tracking.animateTo(1f, tween(950, easing = FastOutSlowInEasing))
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        AuroraBackdrop(Modifier.fillMaxSize())

        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Orbital energy ring + orbiting ions + logo mark.
            AuraStage(
                sizeDp = 148,
                ring = true,
                haloColor = StratosColors.GlowB,
                haloAlpha = 0.24f
            ) {
                LogoTile(
                    modifier = Modifier.graphicsLayer {
                        alpha = appear.value
                        val s = 0.7f + 0.3f * appear.value
                        scaleX = s
                        scaleY = s
                    },
                    sizeDp = 84
                )
            }

            Spacer(Modifier.height(26.dp))

            Text(
                "STRATOS",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (9f * tracking.value).sp,
                fontFamily = StratosFontFamily,
                modifier = Modifier.graphicsLayer {
                    alpha = appear.value
                    translationY = (1f - appear.value) * 26f
                }
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.splash_tagline),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(horizontal = 40.dp)
                    .graphicsLayer { alpha = appear.value }
            )
        }

        // Version chip pinned at the bottom — reads the frozen manifest version.
        Pill(
            text = "v" + BuildConfig.VERSION_NAME,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 34.dp)
                .graphicsLayer { alpha = appear.value }
        )
    }
}

// ---- 2 · Welcome + tour + permissions ---------------------------------------

private enum class LaunchStep { WELCOME, TOUR, PERMISSIONS }

@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
    var step by remember { mutableStateOf(LaunchStep.WELCOME) }

    AnimatedContent(
        targetState = step,
        transitionSpec = {
            // Scale + fade: identical in RTL and LTR (no directional slide).
            (fadeIn(tween(380, easing = FastOutSlowInEasing)) +
                scaleIn(initialScale = 0.955f, animationSpec = tween(380, easing = FastOutSlowInEasing)))
                .togetherWith(fadeOut(tween(240)))
        },
        label = "launchStep"
    ) { s ->
        when (s) {
            LaunchStep.WELCOME -> WelcomeScreen(onStart = { step = LaunchStep.TOUR })
            LaunchStep.TOUR -> TourPager(onDone = { step = LaunchStep.PERMISSIONS })
            LaunchStep.PERMISSIONS -> PermissionsScreen(onFinish = onFinish)
        }
    }
}

// ---- Welcome (شاشة الترحيب) --------------------------------------------------

@Composable
private fun WelcomeScreen(onStart: () -> Unit) {
    // RTL-aware forward nudge for the CTA arrow: the auto-mirrored icon points
    // "forward" in both directions, so the nudge follows the layout direction.
    val forward = if (LocalLayoutDirection.current == LayoutDirection.Ltr) 1f else -1f
    val nudge = rememberInfiniteTransition(label = "nudge").animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(850, easing = LinearEasing), RepeatMode.Reverse),
        label = "nudgeX"
    )

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        AuroraBackdrop(Modifier.fillMaxSize())

        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            StaggerIn(index = 0) {
                AuraStage(
                    sizeDp = 168,
                    ring = false,
                    haloColor = StratosColors.GlowA,
                    haloAlpha = 0.30f,
                    breatheContent = true
                ) {
                    LogoTile(sizeDp = 96)
                }
            }

            Spacer(Modifier.height(28.dp))

            // A hello in all three UI languages — the app's signature.
            // (Mixed-script string: no letter-spacing, it would break the
            // Arabic joins; Cairo keeps the greeting in one brand voice.)
            StaggerIn(index = 1) {
                Text(
                    stringResource(R.string.welcome_greeting),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelLarge
                )
            }
            Spacer(Modifier.height(10.dp))
            StaggerIn(index = 2) {
                Text(
                    stringResource(R.string.welcome_title),
                    color = MaterialTheme.colorScheme.onBackground,
                    style = MaterialTheme.typography.headlineLarge,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(Modifier.height(12.dp))
            StaggerIn(index = 3) {
                Text(
                    stringResource(R.string.welcome_body),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            }

            Spacer(Modifier.height(40.dp))
            StaggerIn(index = 4) {
                GlowButton(onClick = onStart) {
                    Text(
                        stringResource(R.string.cta_get_started),
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge
                    )
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier
                            .size(18.dp)
                            .graphicsLayer { translationX = nudge.value * 3.dp.toPx() * forward }
                    )
                }
            }
        }
    }
}

// ---- App tour (شرح التطبيق) --------------------------------------------------

private data class TourPage(
    val icon: ImageVector,
    val titleRes: Int,
    val bodyRes: Int,
    val points: List<Int>
)

private val tourPages = listOf(
    TourPage(
        Icons.Filled.Dns, R.string.tour1_title, R.string.tour1_body,
        listOf(R.string.tour1_p1, R.string.tour1_p2, R.string.tour1_p3)
    ),
    TourPage(
        Icons.Filled.SmartToy, R.string.tour2_title, R.string.tour2_body,
        listOf(R.string.tour2_p1, R.string.tour2_p2, R.string.tour2_p3)
    ),
    TourPage(
        Icons.Filled.VpnKey, R.string.tour3_title, R.string.tour3_body,
        listOf(R.string.tour3_p1, R.string.tour3_p2, R.string.tour3_p3)
    )
)

@Composable
private fun TourPager(onDone: () -> Unit) {
    val pager = rememberPagerState(pageCount = { tourPages.size })
    val scope = rememberCoroutineScope()

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        AuroraBackdrop(Modifier.fillMaxSize())

        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            // Step counter + skip. The counter rolls vertically on page change
            // (vertical slide is RTL-safe by construction).
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                AnimatedContent(
                    targetState = pager.currentPage + 1,
                    transitionSpec = {
                        (slideInVertically(tween(320, easing = FastOutSlowInEasing)) { it / 2 } +
                            fadeIn(tween(320))) togetherWith
                            (slideOutVertically(tween(200)) { -it / 2 } + fadeOut(tween(140)))
                    },
                    label = "stepCounter"
                ) { n ->
                    Text(
                        stringResource(R.string.domain_step_of, n, tourPages.size),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
                TextButton(onClick = onDone) {
                    Text(stringResource(R.string.onboarding_skip))
                }
            }

            HorizontalPager(
                state = pager,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) { page ->
                TourPageContent(tourPages[page], pager, page)
            }

            // Dots + action.
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp)
                    .padding(bottom = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                TourDots(pager)
                Spacer(Modifier.height(20.dp))
                GlowButton(
                    onClick = {
                        if (pager.currentPage < tourPages.size - 1) {
                            scope.launch {
                                pager.animateScrollToPage(pager.currentPage + 1)
                            }
                        } else {
                            onDone()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        stringResource(
                            if (pager.currentPage == tourPages.size - 1) R.string.tour_done
                            else R.string.tour_next
                        ),
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}

/**
 * Tour dot indicator: springy width, animated color, and a breathing plasma
 * glow behind the active dot. Width change is a spring (bounce), color a
 * quick tween, glow drawn in the draw phase.
 */
@Composable
private fun TourDots(pager: PagerState) {
    val glow = rememberInfiniteTransition(label = "dotGlow").animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Reverse),
        label = "dotGlowA"
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(tourPages.size) { i ->
            val selected = pager.currentPage == i
            val w by animateDpAsState(
                targetValue = if (selected) 26.dp else 8.dp,
                animationSpec = spring(dampingRatio = 0.65f, stiffness = 520f),
                label = "dotW"
            )
            val dotColor by animateColorAsState(
                if (selected) StratosColors.GlowA else MaterialTheme.colorScheme.outlineVariant,
                animationSpec = tween(300),
                label = "dotC"
            )
            Box(
                Modifier
                    .width(w)
                    .height(8.dp)
                    .drawBehind {
                        if (selected) {
                            drawCircle(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        StratosColors.GlowB.copy(alpha = 0.55f * glow.value),
                                        Color.Transparent
                                    ),
                                    radius = size.maxDimension
                                )
                            )
                        }
                    }
                    .clip(RoundedCornerShape(4.dp))
                    .background(dotColor)
            )
        }
    }
}

/**
 * One tour page. Two independent motion systems compose here:
 *
 *  · ENTRANCE — one shared Animatable drives a choreographed sequence:
 *    hero pops (EaseOutBack overshoot) → title lifts in → body lifts in →
 *    the three bullet rows cascade, each check-icon popping with a spring.
 *    Replayed every time the page re-enters the viewport.
 *  · PARALLAX — while swiping, the hero, text and bullets translate at
 *    different depths and the whole layer fades near the edge; offsets are
 *    read from the pager state inside graphicsLayer lambdas (draw phase).
 */
@Composable
private fun TourPageContent(page: TourPage, pager: PagerState, index: Int) {
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(40)
        enter.animateTo(1f, tween(720, easing = FastOutSlowInEasing))
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .graphicsLayer {
                // Whole-layer depth fade near the page edges while swiping.
                val off = (pager.currentPage - index) + pager.currentPageOffsetFraction
                alpha = 1f - 0.38f * abs(off).coerceIn(0f, 1f)
            }
            .padding(horizontal = 28.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Hero: aura stage (ring + orbits + halo) + breathing gradient tile.
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.graphicsLayer {
                val off = (pager.currentPage - index) + pager.currentPageOffsetFraction
                translationX = off * 26.dp.toPx()
                val w = window(enter.value, 0f, 0.42f)
                val s = (0.82f + 0.18f * EaseOutBack.transform(w)).coerceAtLeast(0f)
                scaleX = s
                scaleY = s
            }
        ) {
            AuraStage(
                sizeDp = 148,
                ring = true,
                haloColor = StratosColors.GlowB,
                haloAlpha = 0.26f,
                breatheContent = true
            ) {
                Box(
                    Modifier
                        .size(88.dp)
                        .clip(RoundedCornerShape(28.dp))
                        .background(stratosGlowBrush())
                        .border(
                            1.dp,
                            Brush.linearGradient(
                                listOf(
                                    Color.White.copy(alpha = 0.5f),
                                    Color.White.copy(alpha = 0.12f)
                                )
                            ),
                            RoundedCornerShape(28.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        page.icon,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(42.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(30.dp))
        Text(
            stringResource(page.titleRes),
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.graphicsLayer {
                val w = window(enter.value, 0.16f, 0.56f)
                alpha = w
                translationY = (1f - w) * 30f
                translationX = ((pager.currentPage - index) + pager.currentPageOffsetFraction) * 12.dp.toPx()
            }
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(page.bodyRes),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.graphicsLayer {
                val w = window(enter.value, 0.28f, 0.68f)
                alpha = w
                translationY = (1f - w) * 24f
            }
        )
        Spacer(Modifier.height(26.dp))

        page.points.forEachIndexed { i, pointRes ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 7.dp)
                    .graphicsLayer {
                        val w = window(enter.value, 0.38f + i * 0.11f, 0.70f + i * 0.11f)
                        alpha = w
                        translationY = (1f - w) * 22f
                    }
                    .stratosGlass(RoundedCornerShape(16.dp))
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(glassFill(stratosIsDark()), CircleShape)
                        .border(
                            1.dp,
                            glassEdgeBrush(stratosIsDark()),
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .size(15.dp)
                            .graphicsLayer {
                                // The check pops last — overshoot then settle.
                                val w = window(enter.value, 0.46f + i * 0.11f, 0.78f + i * 0.11f)
                                val s = (EaseOutBack.transform(w)).coerceAtLeast(0f)
                                scaleX = s
                                scaleY = s
                            }
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(pointRes),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

// ---- Permissions (طلب الأذونات) ----------------------------------------------

@Composable
private fun PermissionsScreen(onFinish: () -> Unit) {
    val context = LocalContext.current

    // Refresh permission snapshots when returning from system dialogs —
    // a lightweight poll that only lives on this screen.
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(700)
            refresh++
        }
    }

    // Permission snapshots re-evaluated whenever the poll ticks (e.g. after
    // returning from the system permission dialog or battery settings).
    val notifGranted = remember(refresh) {
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    val legacyStorageNeeded = Build.VERSION.SDK_INT <= 32
    val storageGranted = remember(refresh) {
        !legacyStorageNeeded || ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }

    val batteryGranted = remember(refresh) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    val storageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        AuroraBackdrop(Modifier.fillMaxSize())

        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(22.dp))
            StaggerIn(index = 0) {
                Text(
                    stringResource(R.string.perm_title),
                    color = MaterialTheme.colorScheme.onBackground,
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(Modifier.height(8.dp))
            StaggerIn(index = 1) {
                Text(
                    stringResource(R.string.perm_subtitle),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(Modifier.height(22.dp))

            // Notifications — required for background hosting status on 13+.
            StaggerIn(index = 2) {
                PermissionCard(
                    icon = Icons.Filled.Notifications,
                    title = stringResource(R.string.perm_notif_title),
                    body = stringResource(R.string.perm_notif_body),
                    granted = notifGranted,
                    supported = true,
                    actionLabel = stringResource(R.string.perm_allow),
                    onAction = {
                        if (Build.VERSION.SDK_INT >= 33) {
                            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                )
            }

            // Legacy storage — only exists up to Android 12; modern Android
            // grants ZIP access through the picker/share target with no permission.
            if (legacyStorageNeeded) {
                StaggerIn(index = 3) {
                    PermissionCard(
                        icon = Icons.Filled.FolderOpen,
                        title = stringResource(R.string.perm_files_title),
                        body = stringResource(R.string.perm_files_body),
                        granted = storageGranted,
                        supported = true,
                        actionLabel = stringResource(R.string.perm_allow),
                        onAction = { storageLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE) }
                    )
                }
            }

            // Battery optimization exemption — keeps the server alive.
            StaggerIn(index = if (legacyStorageNeeded) 4 else 3) {
                PermissionCard(
                    icon = Icons.Filled.Bolt,
                    title = stringResource(R.string.perm_battery_title),
                    body = stringResource(R.string.perm_battery_body),
                    granted = batteryGranted,
                    supported = true,
                    actionLabel = stringResource(R.string.perm_open_settings),
                    onAction = { requestBatteryExemption(context) }
                )
            }

            Spacer(Modifier.height(8.dp))
            StaggerIn(index = 5) {
                Text(
                    stringResource(R.string.diag_privacy),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            }

            Spacer(Modifier.height(24.dp))
            StaggerIn(index = 6) {
                GlowButton(
                    onClick = onFinish,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        stringResource(R.string.perm_finish),
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onFinish, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.perm_later),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Direct allow-dialog for battery optimization, with a settings-list fallback. */
private fun requestBatteryExemption(context: Context) {
    runCatching {
        context.startActivity(
            Intent(
                AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${context.packageName}")
            )
        )
    }.onFailure {
        runCatching {
            context.startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
}

@Composable
private fun PermissionCard(
    icon: ImageVector,
    title: String,
    body: String,
    granted: Boolean,
    supported: Boolean,
    actionLabel: String,
    onAction: () -> Unit
) {
    val shape = RoundedCornerShape(20.dp)

    // Grant moment: the status pill morphs color and pulses once (spring).
    val pillScale = remember { Animatable(1f) }
    LaunchedEffect(granted) {
        if (granted) {
            pillScale.snapTo(1.35f)
            pillScale.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 520f))
        }
    }
    val statusColor by animateColorAsState(
        if (granted) StratosColors.Success else StratosColors.Warning,
        animationSpec = tween(300),
        label = "statusColor"
    )

    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp)
            .stratosGlass(shape)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(stratosGlowBrush()),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(23.dp))
            }
            Spacer(Modifier.width(13.dp))
            Text(
                title,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            Pill(
                text = stringResource(if (granted) R.string.perm_allowed else R.string.perm_not_yet),
                container = statusColor,
                content = statusColor,
                modifier = Modifier.graphicsLayer {
                    scaleX = pillScale.value
                    scaleY = pillScale.value
                }
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            body,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall
        )
        if (!granted && supported) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onAction) {
                    Text(actionLabel)
                }
            }
        }
    }
}
