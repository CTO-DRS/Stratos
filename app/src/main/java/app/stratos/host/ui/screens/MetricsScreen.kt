package app.stratos.host.ui.screens

import android.app.ActivityManager
import android.content.Context
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.StatFs
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import app.stratos.host.R
import app.stratos.host.StratosApp
import app.stratos.host.engine.EngineStatus
import app.stratos.host.ui.components.Pill
import app.stratos.host.ui.components.SectionCard
import app.stratos.host.ui.components.StatCard
import app.stratos.host.ui.components.StatusDot
import app.stratos.host.ui.theme.StratosColors
import app.stratos.host.util.Formatters
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

data class DeviceMetrics(
    val memUsedMb: Long = -1,
    val memTotalMb: Long = -1,
    val storageUsedGb: Double = -1.0,
    val storageTotalGb: Double = -1.0,
    val batteryTempC: Double = -1.0,
    val rxBytes: Long = -1,
    val txBytes: Long = -1,
    val cpuAvailable: Boolean = false,
    val cpuPercent: Double = 0.0
)

class MetricsViewModel : ViewModel() {

    private val graph = StratosApp.instance.graph

    private val _status = MutableStateFlow<EngineStatus?>(null)
    val status: StateFlow<EngineStatus?> = _status.asStateFlow()

    private val _device = MutableStateFlow(DeviceMetrics())
    val device: StateFlow<DeviceMetrics> = _device.asStateFlow()

    /** Requests-per-second sparkline samples (60s window). */
    private val _rps = MutableStateFlow<List<Double>>(emptyList())
    val rps: StateFlow<List<Double>> = _rps.asStateFlow()

    private var lastRequests = 0L

    init {
        viewModelScope.launch {
            graph.controller.metricsFlow(1000).collect { st ->
                _status.value = st
                val cur = st?.requests ?: 0
                val delta = if (cur >= lastRequests && lastRequests > 0) (cur - lastRequests).toDouble() else 0.0
                lastRequests = cur
                _rps.value = (_rps.value + listOf(delta)).takeLast(60)
            }
        }
        viewModelScope.launch {
            while (true) {
                _device.value = sampleDevice()
                kotlinx.coroutines.delay(1000)
            }
        }
    }

    fun reset() = viewModelScope.launch { graph.controller.resetMetrics() }

    private fun sampleDevice(): DeviceMetrics {
        val context = StratosApp.instance
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val totalMb = mem.totalMem / (1024 * 1024)
        val usedMb = (mem.totalMem - mem.availMem) / (1024 * 1024)

        val stat = StatFs(context.filesDir.absolutePath)
        val stTotalGb = stat.totalBytes / (1024.0 * 1024 * 1024)
        val stUsedGb = (stat.totalBytes - stat.availableBytes) / (1024.0 * 1024 * 1024)

        // Temperature comes from the ACTION_BATTERY_CHANGED sticky broadcast
        // (tenths of a degree Celsius). There is no BATTERY_PROPERTY_TEMPERATURE.
        val batteryIntent = context.registerReceiver(null, IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val tempRaw = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        val temp = tempRaw.takeIf { it > 0 }?.toDouble()?.div(10.0) ?: -1.0

        val rx = android.net.TrafficStats.getTotalRxBytes().takeIf { it > 0 } ?: -1L
        val tx = android.net.TrafficStats.getTotalTxBytes().takeIf { it > 0 } ?: -1L

        // CPU: honest telemetry — when the platform hides /proc/stat we say so.
        val (cpuOk, cpuPct) = sampleCpu()

        return DeviceMetrics(
            memUsedMb = usedMb, memTotalMb = totalMb,
            storageUsedGb = stUsedGb, storageTotalGb = stTotalGb,
            batteryTempC = temp, rxBytes = rx, txBytes = tx,
            cpuAvailable = cpuOk, cpuPercent = cpuPct
        )
    }

    private var prevCpu: LongArray? = null
    private var prevCpuTime = 0L

    private fun sampleCpu(): Pair<Boolean, Double> {
        return try {
            val lines = java.io.File("/proc/stat").readLines()
            val cpu = lines.firstOrNull { it.startsWith("cpu ") } ?: return false to 0.0
            val parts = cpu.split(Regex("\\s+")).drop(1).map { it.toLong() }
            val idle = parts.getOrElse(3) { 0 } + parts.getOrElse(4) { 0 } // idle + iowait
            val total = parts.sum()
            val prev = prevCpu
            prevCpu = longArrayOf(idle, total)
            val now = System.currentTimeMillis()
            val dt = now - prevCpuTime
            prevCpuTime = now
            if (prev == null || dt < 400) return true to 0.0
            val dIdle = idle - prev[0]
            val dTotal = total - prev[1]
            if (dTotal <= 0) return true to 0.0
            val pct = (1.0 - dIdle.toDouble() / dTotal) * 100.0
            true to pct.coerceIn(0.0, 100.0)
        } catch (_: Exception) {
            false to 0.0 // SELinux denies /proc/stat — report unavailable, never fake
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MetricsScreen(nav: NavHostController, vm: MetricsViewModel = viewModel()) {
    val status by vm.status.collectAsState()
    val device by vm.device.collectAsState()
    val rps by vm.rps.collectAsState()

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.nav_metrics)) },
            actions = {
                OutlinedButton(
                    onClick = { vm.reset() },
                    modifier = Modifier.padding(end = 12.dp)
                ) { Text(stringResource(R.string.reset)) }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
        )

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusDot(if (status?.running == true) StratosColors.Success else MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    if (status?.running == true) stringResource(R.string.engine_live) else stringResource(R.string.engine_idle),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.weight(1f))
                status?.uptimeSec?.takeIf { it > 0 }?.let {
                    Text(Formatters.duration(it), style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
                }
            }

            // ---- Requests/sec sparkline ----
            SectionCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.metric_rps), style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.weight(1f))
                        Text(
                            String.format(Locale.US, "%.0f", rps.lastOrNull() ?: 0.0) + " req/s",
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = FontFamily.Monospace,
                            color = StratosColors.Accent
                        )
                    }
                    Sparkline(
                        values = rps,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(90.dp)
                    )
                }
            }

            // ---- Latency + requests ----
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard(stringResource(R.string.stat_requests), Formatters.count(status?.requests ?: 0), Modifier.weight(1f))
                StatCard(stringResource(R.string.stat_active), Formatters.count(status?.active ?: 0), Modifier.weight(1f), valueColor = StratosColors.Accent)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard(stringResource(R.string.stat_p50), Formatters.ms(status?.p50Ms ?: 0.0), Modifier.weight(1f))
                StatCard(stringResource(R.string.stat_p95), Formatters.ms(status?.p95Ms ?: 0.0), Modifier.weight(1f))
                StatCard(stringResource(R.string.stat_max), Formatters.ms(status?.maxMs ?: 0.0), Modifier.weight(1f))
            }

            // ---- Status codes ----
            SectionCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.metric_status_codes), style = MaterialTheme.typography.titleSmall)
                    val byStatus = status?.byStatus.orEmpty()
                    if (byStatus.isEmpty()) {
                        Text(
                            stringResource(R.string.metric_no_data),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        val max = byStatus.values.maxOrNull() ?: 1
                        byStatus.entries.sortedByDescending { it.value }.forEach { (code, n) ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(code, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(end = 4.dp))
                                StatusDot(statusColor(code))
                                Bar(
                                    fraction = n / max.toFloat(),
                                    color = statusColor(code),
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    Formatters.count(n.toLong()),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }

            // ---- Device (honest telemetry) ----
            SectionCard(
                header = {
                    Text(stringResource(R.string.metric_device), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.weight(1f))
                    Pill(stringResource(R.string.metric_device_src))
                }
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatCard(
                            stringResource(R.string.metric_memory),
                            if (device.memTotalMb > 0) "${device.memUsedMb}/${device.memTotalMb} MB" else "—",
                            Modifier.weight(1f)
                        )
                        StatCard(
                            stringResource(R.string.metric_cpu),
                            if (device.cpuAvailable) String.format(Locale.US, "%.0f%%", device.cpuPercent)
                            else stringResource(R.string.metric_unavailable),
                            Modifier.weight(1f),
                            valueColor = if (device.cpuAvailable) MaterialTheme.colorScheme.onSurface else StratosColors.Warning
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatCard(
                            stringResource(R.string.metric_storage),
                            if (device.storageTotalGb > 0) String.format(Locale.US, "%.0f/%.0f GB", device.storageUsedGb, device.storageTotalGb) else "—",
                            Modifier.weight(1f)
                        )
                        StatCard(
                            stringResource(R.string.metric_battery_temp),
                            if (device.batteryTempC > 0) String.format(Locale.US, "%.1f°C", device.batteryTempC) else stringResource(R.string.metric_unavailable),
                            Modifier.weight(1f)
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatCard(stringResource(R.string.metric_rx), if (device.rxBytes >= 0) Formatters.bytes(device.rxBytes) else "—", Modifier.weight(1f))
                        StatCard(stringResource(R.string.metric_tx), if (device.txBytes >= 0) Formatters.bytes(device.txBytes) else "—", Modifier.weight(1f))
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

private fun statusColor(code: String): androidx.compose.ui.graphics.Color = when {
    code.startsWith("2") -> StratosColors.Success
    code.startsWith("3") -> StratosColors.Accent
    code.startsWith("4") -> StratosColors.Warning
    code.startsWith("5") -> StratosColors.Error
    else -> MaterialThemeColorFallback
}

// Non-composable color fallback for status bars drawn outside composition.
private val MaterialThemeColorFallback = StratosColors.Accent

@Composable
private fun Bar(fraction: Float, color: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    Canvas(modifier.height(8.dp)) {
        val w = size.width * fraction.coerceIn(0f, 1f)
        drawRoundRect(
            color = color,
            size = androidx.compose.ui.geometry.Size(w, size.height),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f, 6f)
        )
    }
}

/** Minimal sparkline (accent stroke, soft fill). */
@Composable
private fun Sparkline(values: List<Double>, modifier: Modifier = Modifier) {
    val lineColor = StratosColors.Accent
    val fillColor = StratosColors.AccentSoft
    Canvas(modifier.background(fillColor, RoundedCornerShape(10.dp))) {
        if (values.size < 2) return@Canvas
        val maxV = (values.maxOrNull() ?: 1.0).coerceAtLeast(1.0)
        val stepX = size.width / (values.size - 1)
        val scaleY = size.height * 0.9f

        val path = Path()
        values.forEachIndexed { i, v ->
            val x = i * stepX
            val y = size.height - (v / maxV * scaleY).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        val fill = Path().apply {
            addPath(path)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(fill, fillColor)
        drawPath(path, lineColor, style = Stroke(width = 4f, cap = StrokeCap.Round))
        // last point marker
        val lastX = (values.size - 1) * stepX
        val lastY = size.height - ((values.last() / maxV) * scaleY).toFloat()
        drawCircle(lineColor, radius = 8f, center = Offset(lastX, lastY))
    }
}
