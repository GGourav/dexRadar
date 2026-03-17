package com.dexradar.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.dexradar.MainApplication
import com.dexradar.R
import com.dexradar.parser.EventDispatcher
import com.dexradar.parser.PhotonParser
import kotlinx.coroutines.*
import java.io.FileInputStream

class AlbionVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var readJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var dispatcher: EventDispatcher

    companion object {
        const val NOTIF_CHANNEL_ID = "dexradar_vpn"
        const val NOTIF_ID = 1
    }

    override fun onCreate() {
        super.onCreate()
        dispatcher = EventDispatcher(
            MainApplication.idMapRepository,
            MainApplication.discoveryLogger
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }

        // CRITICAL: Acquire WakeLock BEFORE starting read thread
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "DexRadar:VpnWake"
        ).also { it.acquire() }

        establishVpn()
        return START_STICKY
    }

    override fun onDestroy() {
        readJob?.cancel()
        vpnInterface?.close()
        wakeLock?.release()
        super.onDestroy()
        broadcastStatus(false)
    }

    private fun establishVpn() {
        val tun = Builder()
            .setMtu(32767)
            .addAddress("10.0.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("8.8.8.8")
            .setSession("Dex Radar")
            .addAllowedApplication("com.albiononline")
            .establish()
            ?: run { stopSelf(); return }

        vpnInterface = tun
        broadcastStatus(true)
        startReadLoop(tun)
    }

    private fun startReadLoop(tun: ParcelFileDescriptor) {
        readJob = CoroutineScope(Dispatchers.IO).launch {
            val buffer = ByteArray(65536)
            val inputStream = FileInputStream(tun.fileDescriptor)
            val photonParser = PhotonParser(dispatcher)
            try {
                while (isActive) {
                    val length = inputStream.read(buffer)
                    if (length > 0) processIpPacket(buffer, length, photonParser)
                }
            } catch (e: Exception) {
                // VPN read loop ends on service destroy — silently exit
            }
        }
    }

    private fun processIpPacket(buffer: ByteArray, length: Int, parser: PhotonParser) {
        if (length < 20) return

        // IP version — only handle IPv4
        val version = (buffer[0].toInt() and 0xF0) ushr 4
        if (version != 4) return

        // Protocol — only handle UDP (17)
        val protocol = buffer[9].toInt() and 0xFF
        if (protocol != 17) return

        // IP header length
        val ipHeaderLen = (buffer[0].toInt() and 0x0F) * 4
        if (length < ipHeaderLen + 8) return

        // Destination port (big-endian)
        val dstPort = ((buffer[ipHeaderLen + 2].toInt() and 0xFF) shl 8) or
                       (buffer[ipHeaderLen + 3].toInt() and 0xFF)

        // Only Photon port 5056
        if (dstPort != 5056) return

        val payloadStart  = ipHeaderLen + 8
        val payloadLength = length - payloadStart
        if (payloadLength < 12) return

        parser.parse(buffer, payloadStart, payloadLength)
    }

    private fun broadcastStatus(running: Boolean) {
        LocalBroadcastManager.getInstance(this)
            .sendBroadcast(Intent("com.dexradar.VPN_STATUS")
                .putExtra("running", running))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID, "Dex Radar VPN",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification() = NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
        .setContentTitle("Dex Radar Active")
        .setContentText("Capturing Albion Online traffic on port 5056")
        .setSmallIcon(R.drawable.ic_radar_notif)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setOngoing(true)
        .setSilent(true)
        .build()
}
