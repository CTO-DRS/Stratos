package app.stratos.host.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import app.stratos.host.MainActivity
import app.stratos.host.R
import app.stratos.host.StratosApp
import app.stratos.host.host.HostState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service (specialUse) that keeps the embedded server serving with
 * the screen off: partial wake lock + high-perf Wi-Fi lock, a persistent
 * notification with a STOP action, and clean shutdown on every exit path.
 */
class HostingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopHostingAndSelf()
                return START_NOT_STICKY
            }
            else -> startHostingForeground()
        }
        return START_STICKY
    }

    private fun startHostingForeground() {
        createChannel()
        val notification = buildNotification(running = false)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        acquireLocks()

        val controller = StratosApp.instance.graph.controller
        scope.launch {
            var seenActive = false
            controller.state.collect { state ->
                if (state is HostState.Running) seenActive = true
                val nm = getSystemService(NotificationManager::class.java)
                nm?.notify(NOTIFICATION_ID, buildNotification(state is HostState.Running))
                // Stop only after a real lifecycle, never on the initial Idle.
                if (state is HostState.Failed || (state is HostState.Idle && seenActive)) {
                    stopSelf()
                }
            }
        }
        scope.launch { controller.start() }
    }

    private fun stopHostingAndSelf() {
        val controller = StratosApp.instance.graph.controller
        scope.launch {
            controller.stop()
            releaseLocks()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        releaseLocks()
        scope.cancel()
        super.onDestroy()
    }

    private fun acquireLocks() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "stratos:hosting").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        if (wifiLock == null) {
            wifiLock = app.stratos.host.util.NetUtils.keepWifiAlive(this)
        }
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        wifiLock = null
    }

    private fun buildNotification(running: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, HostingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = if (running) getString(R.string.notif_title_running) else getString(R.string.notif_title_starting)
        val text = if (running) getString(R.string.notif_text_running) else getString(R.string.notif_text_starting)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim) // replaced by vector in res if desired
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.notif_action_stop), stop)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notif_channel_desc)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "stratos_hosting"
        const val NOTIFICATION_ID = 0x5354 // "ST"
        const val ACTION_STOP = "app.stratos.host.action.STOP"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, HostingService::class.java))
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, HostingService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
