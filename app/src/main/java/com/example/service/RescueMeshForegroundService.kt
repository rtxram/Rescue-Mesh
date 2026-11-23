package com.example.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.RescueMeshApplication
import com.example.domain.model.EmergencySession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Persistent background BLE mesh relay service.
 * Displays an explicit notification whenever relay participation is active and
 * alerts the user on high-priority incoming SOS sessions.
 */
class RescueMeshForegroundService : Service() {

    companion object {
        const val CHANNEL_RELAY = "rescuemesh_relay_channel"
        const val CHANNEL_ALERTS = "rescuemesh_alerts_channel"
        const val NOTIFICATION_ID_RELAY = 1001
        const val ACTION_STOP = "com.rescuemesh.app.ACTION_STOP_RELAY"

        fun hasConnectedDevicePermission(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
                    PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) ==
                    PackageManager.PERMISSION_GRANTED
            } else {
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
            }
        }

        fun hasLocationPermission(context: Context): Boolean {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        }

        fun hasAnyFgsPermission(context: Context): Boolean =
            hasConnectedDevicePermission(context) || hasLocationPermission(context)

        fun start(context: Context): Boolean {
            if (!hasAnyFgsPermission(context)) {
                return false
            }
            val intent = Intent(context, RescueMeshForegroundService::class.java)
            return runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                true
            }.getOrDefault(false)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RescueMeshForegroundService::class.java))
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var monitorJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val app = application as RescueMeshApplication
        val notification = buildRelayNotification(peerCount = 0, pendingCount = 0)

        var serviceTypes = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (hasConnectedDevicePermission(this)) {
                serviceTypes = serviceTypes or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            }
            if (hasLocationPermission(this)) {
                serviceTypes = serviceTypes or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && serviceTypes == 0) {
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && serviceTypes != 0) {
                startForeground(NOTIFICATION_ID_RELAY, notification, serviceTypes)
            } else {
                startForeground(NOTIFICATION_ID_RELAY, notification)
            }
        } catch (_: SecurityException) {
            stopSelf()
            return START_NOT_STICKY
        }

        app.meshCoordinator.start()

        monitorJob?.cancel()
        monitorJob = serviceScope.launch {
            launch {
                combine(
                    app.repository.observePeers(),
                    app.repository.observePendingRelayCount()
                ) { peers, pending ->
                    val recentPeers = peers.count {
                        System.currentTimeMillis() - it.lastSeenAtMillis < 120_000L
                    }
                    recentPeers to pending
                }.collect { (peerCount, pendingCount) ->
                    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.notify(NOTIFICATION_ID_RELAY, buildRelayNotification(peerCount, pendingCount))
                }
            }
            launch {
                app.meshCoordinator.incomingAlertEvents.collect { session ->
                    postIncomingSosNotification(session)
                }
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        monitorJob?.cancel()
        serviceScope.launch {
            (application as RescueMeshApplication).meshCoordinator.stop()
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val relayChannel = NotificationChannel(
                CHANNEL_RELAY,
                getString(R.string.fgs_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.fgs_channel_desc)
            }
            val alertChannel = NotificationChannel(
                CHANNEL_ALERTS,
                getString(R.string.alert_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.alert_channel_desc)
                enableVibration(true)
            }
            nm.createNotificationChannel(relayChannel)
            nm.createNotificationChannel(alertChannel)
        }
    }

    private fun buildRelayNotification(peerCount: Int, pendingCount: Int): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_RELAY)
            .setSmallIcon(R.drawable.ic_sos_tile)
            .setContentTitle("RescueMesh Relay Active")
            .setContentText("Peers nearby: $peerCount · Store-carry-forward queue: $pendingCount")
            .setOngoing(true)
            .setContentIntent(openIntent)
            .build()
    }

    private fun postIncomingSosNotification(session: EmergencySession) {
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_OPEN_SESSION_ID, session.sessionId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            this,
            session.sessionId.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notif = NotificationCompat.Builder(this, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_sos_tile)
            .setContentTitle("SOS Alert from ${session.creatorDisplayName}")
            .setContentText("${session.context.summary} · Hop ${session.hopCount}")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(session.sessionId.hashCode(), notif)
    }
}
