package org.flowseal.tgwsproxy.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.flowseal.tgwsproxy.R
import org.flowseal.tgwsproxy.TgWsApplication
import org.flowseal.tgwsproxy.core.NativeProxyRunner
import org.flowseal.tgwsproxy.receiver.ProxyActionReceiver
import org.flowseal.tgwsproxy.ui.MainActivity

data class ProxyStats(
    val active: Int = 0,
    val total: Int = 0,
    val upMb: Double = 0.0,
    val downMb: Double = 0.0
)

class TgWsProxyService : Service() {

    private lateinit var runner: NativeProxyRunner
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    companion object {
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "org.flowseal.tgwsproxy.START"
        const val ACTION_STOP = "org.flowseal.tgwsproxy.STOP"

        const val EXTRA_PORT = "EXTRA_PORT"
        const val EXTRA_SECRET = "EXTRA_SECRET"
        const val EXTRA_DOMAIN = "EXTRA_DOMAIN"
        const val EXTRA_CF_WORKER = "EXTRA_CF_WORKER"

        private val _isRunning = MutableStateFlow(false)
        val isRunning = _isRunning.asStateFlow()

        private val _logFlow = MutableSharedFlow<String>(replay = 50)
        val logFlow = _logFlow.asSharedFlow()

        private val _statsFlow = MutableStateFlow(ProxyStats())
        val statsFlow = _statsFlow.asStateFlow()

        private val statsRegex = Regex("""\[Stats\]\s*Active:\s*(\d+)\s*\|\s*Total:\s*(\d+).*?\|\s*Up:\s*([\d.]+)\s*MB\s*\|\s*Down:\s*([\d.]+)\s*MB""")

        var currentPort: Int = 1443
        var currentSecret: String = ""
        var currentDomain: String = ""
    }

    override fun onCreate() {
        super.onCreate()
        runner = NativeProxyRunner(this)
        runner.setLogListener { line ->
            _logFlow.tryEmit(line)
            val match = statsRegex.find(line)
            if (match != null) {
                val active = match.groupValues[1].toIntOrNull() ?: 0
                val total = match.groupValues[2].toIntOrNull() ?: 0
                val upMb = match.groupValues[3].toDoubleOrNull() ?: 0.0
                val downMb = match.groupValues[4].toDoubleOrNull() ?: 0.0
                _statsFlow.value = ProxyStats(active, total, upMb, downMb)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopProxy()
                stopSelf()
            }
            ACTION_START, null -> {
                val port = intent?.getIntExtra(EXTRA_PORT, 1443) ?: 1443
                val secret = intent?.getStringExtra(EXTRA_SECRET) ?: ""
                val domain = intent?.getStringExtra(EXTRA_DOMAIN) ?: "google.com"
                val cfWorker = intent?.getStringExtra(EXTRA_CF_WORKER) ?: ""

                currentPort = port
                currentSecret = secret
                currentDomain = domain

                startProxy(port, secret, domain, cfWorker)
            }
        }
        return START_STICKY
    }

    private fun startProxy(port: Int, secret: String, domain: String, cfWorker: String) {
        acquireLocks()

        val notification = buildNotification(port)
        startForeground(NOTIFICATION_ID, notification)

        val success = runner.start(port, secret, domain, cfWorker)
        _isRunning.value = success
    }

    private fun stopProxy() {
        runner.stop()
        releaseLocks()
        _isRunning.value = false
        _statsFlow.value = ProxyStats()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun buildNotification(port: Int): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, ProxyActionReceiver::class.java).apply {
            action = ProxyActionReceiver.ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getBroadcast(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Connect Intent
        val tgUri = Uri.parse("tg://proxy?server=127.0.0.1&port=$port&secret=$currentSecret")
        val connectIntent = Intent(Intent.ACTION_VIEW, tgUri).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val connectPendingIntent = PendingIntent.getActivity(
            this, 2, connectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, TgWsApplication.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("TG WS Proxy активен")
            .setContentText("Порт: 127.0.0.1:$port (Шлюз: $currentDomain)")
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .addAction(R.drawable.ic_telegram, getString(R.string.action_connect), connectPendingIntent)
            .addAction(R.drawable.ic_stop, getString(R.string.action_stop), stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "TgWsProxy::ServiceWakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire(12 * 60 * 60 * 1000L) // 12 hours max
        }

        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifiManager.createWifiLock(
            WifiManager.WIFI_MODE_FULL_HIGH_PERF,
            "TgWsProxy::ServiceWifiLock"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        stopProxy()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
