package app.stratos.host.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket

/** LAN discovery + small network helpers. */
object NetUtils {

    /** Best-effort site-local IPv4 address (Wi-Fi/Ethernet), null when offline. */
    fun lanIp(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            var fallback: String? = null
            for (nif in interfaces.asSequence()) {
                if (!nif.isUp || nif.isLoopback || nif.isVirtual) continue
                for (addr in nif.inetAddresses.asSequence()) {
                    if (addr is InetAddress && addr.hostAddress != null) {
                        val host = addr.hostAddress!!
                        if (addr.isSiteLocalAddress && host.indexOf(':') < 0) {
                            return host
                        }
                        if (fallback == null && host.indexOf(':') < 0 && host != "127.0.0.1") {
                            fallback = host
                        }
                    }
                }
            }
            return fallback
        } catch (_: Exception) {
            return null
        }
    }

    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun keepWifiAlive(context: Context): WifiManager.WifiLock? = try {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "stratos:wifi").apply {
            setReferenceCounted(false)
            acquire()
        }
    } catch (_: Exception) {
        null
    }

    fun portAvailable(port: Int): Boolean = try {
        ServerSocket(port).use { true }
    } catch (_: Exception) {
        false
    }
}
