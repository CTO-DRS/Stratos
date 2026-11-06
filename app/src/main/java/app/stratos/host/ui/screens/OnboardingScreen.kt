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
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import app.stratos.host.ui.theme.glassEdgeBrush
import app.stratos.host.ui.theme.glassFill
import app.stratos.host.ui.theme.stratosGlowBrush
import app.stratos.host.ui.theme.stratosGlass
import app.stratos.host.ui.theme.stratosIsDark
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.Manifest
import android.content.pm.PackageManager

// ---------------------------------------------------------------------------
// Launch experience: splash → welcome → app tour → permissions → main app.
// Every surface is trilingual (AR/EN/TR) and RTL-safe; the aurora + glass
// language is the same as the rest of the 2035 interface.
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

// ---- 1 · Splash (شاشة البداية) ----------------------------------------------

@Composable
fun SplashScreen() {
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        appear.animateTo(1f, tween(950, easing = FastOutSlowInEasing))
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
            // Orbital energy ring + logo mark.
            Box(contentAlignment = Alignment.Center) {
                val transition = rememberInfiniteTransition(label = "ring")
                val rot by transition.animateFloat(
                    initialValue = 0f,
                    targetValue = 360f,
                    animationSpec = infiniteRepeatable(tween(5200, easing = LinearEasing)),
                    label = "ringRot"
                )
                Box(
                    Modifier
                        .size(148.dp)
                        .graphicsLayer { alpha = appear.value }
                        .drawBehind {
                            rotate(rot) {
                                drawCircle(
                                    brush = Brush.sweepGradient(
                                        0f to Color.Transparent,
                                        0.30f to StratosColors.GlowA.copy(alpha = 0.9f),
                                        0.55f to StratosColors.GlowB.copy(alpha = 0.75f),
                                        0.80f to StratosColors.GlowC.copy(alpha = 0.9f),
                                        1f to Color.Transparent
                                    ),
                                    style = Stroke(
                                        width = 2.dp.toPx(),
                                        cap = StrokeCap.Round
                                    )
                                )
                            }
                            // Soft ion halo under the ring.
                            drawCircle(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        StratosColors.GlowB.copy(alpha = 0.22f),
                                        Color.Transparent
                                    ),
                                    radius = size.minDimension / 2f
                                )
                            )
                        }
                )
                LogoTile(
                    modifier = Modifier.graphicsLayer {
                        alpha = appear.value
                        scaleX = 0.7f + 0.3f * appear.value
                        scaleY = 0.7f + 0.3f * appear.value
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
                letterSpacing = 9.sp,
                fontFamily = FontFamily.SansSerif,
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
        transitionSpec = { fadeIn(tween(360)) togetherWith fadeOut(tween(240)) },
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
            Box(contentAlignment = Alignment.Center) {
                val transition = rememberInfiniteTransition(label = "welcomeHalo")
                val breathe by transition.animateFloat(
                    initialValue = 0.55f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Reverse),
                    label = "halo"
                )
                Box(
                    Modifier
                        .size(168.dp)
                        .drawBehind {
                            drawCircle(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        StratosColors.GlowA.copy(alpha = 0.30f * breathe),
                                        StratosColors.GlowB.copy(alpha = 0.14f * breathe),
                                        Color.Transparent
                                    ),
                                    radius = size.minDimension / 2f
                                )
                            )
                        }
                )
                LogoTile(sizeDp = 96)
            }

            Spacer(Modifier.height(28.dp))

            // A hello in all three UI languages — the app's signature.
            Text(
                stringResource(R.string.welcome_greeting),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge,
                letterSpacing = 1.sp
            )
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.welcome_title),
                color = MaterialTheme.colorScheme.onBackground,
                style = MaterialTheme.typography.headlineLarge,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.welcome_body),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 8.dp)
            )

            Spacer(Modifier.height(40.dp))
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
                    modifier = Modifier.size(18.dp)
                )
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
            // Step counter + skip.
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.domain_step_of, pager.currentPage + 1, tourPages.size),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelLarge
                )
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
                TourPageContent(tourPages[page])
            }

            // Dots + action.
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp)
                    .padding(bottom = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(tourPages.size) { i ->
                        val selected = pager.currentPage == i
                        val w by animateDpAsState(
                            targetValue = if (selected) 26.dp else 8.dp,
                            animationSpec = tween(280),
                            label = "dotW"
                        )
                        Box(
                            Modifier
                                .width(w)
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(
                                    if (selected) stratosGlowBrush()
                                    else MaterialTheme.colorScheme.outlineVariant
                                )
                        )
                    }
                }
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

@Composable
private fun TourPageContent(page: TourPage) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Gradient hero tile with halo.
        Box(contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(132.dp)
                    .drawBehind {
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    StratosColors.GlowB.copy(alpha = 0.26f),
                                    Color.Transparent
                                ),
                                radius = size.minDimension / 2f
                            )
                        )
                    }
            )
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

        Spacer(Modifier.height(30.dp))
        Text(
            stringResource(page.titleRes),
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(page.bodyRes),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(26.dp))

        page.points.forEach { pointRes ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 7.dp)
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
                        modifier = Modifier.size(15.dp)
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
            Text(
                stringResource(R.string.perm_title),
                color = MaterialTheme.colorScheme.onBackground,
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.perm_subtitle),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(22.dp))

            // Notifications — required for background hosting status on 13+.
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

            // Legacy storage — only exists up to Android 12; modern Android
            // grants ZIP access through the picker/share target with no permission.
            if (legacyStorageNeeded) {
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

            // Battery optimization exemption — keeps the server alive.
            PermissionCard(
                icon = Icons.Filled.Bolt,
                title = stringResource(R.string.perm_battery_title),
                body = stringResource(R.string.perm_battery_body),
                granted = batteryGranted,
                supported = true,
                actionLabel = stringResource(R.string.perm_open_settings),
                onAction = { requestBatteryExemption(context) }
            )

            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.diag_privacy),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 8.dp)
            )

            Spacer(Modifier.height(24.dp))
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
            TextButton(onClick = onFinish) {
                Text(
                    stringResource(R.string.perm_later),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
                container = if (granted) StratosColors.Success else StratosColors.Warning,
                content = if (granted) StratosColors.Success else StratosColors.Warning
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
