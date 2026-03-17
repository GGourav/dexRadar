package com.dexradar.overlay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.dexradar.R
import com.dexradar.model.RadarEntity
import java.util.concurrent.ConcurrentHashMap

class RadarOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var radarView: RadarSurfaceView? = null

    companion object {
        const val NOTIF_CHANNEL_ID = "dexradar_overlay"
        const val NOTIF_ID = 2

        // Static entity store — VPN thread writes, render thread reads
        private val entityMap = ConcurrentHashMap<Int, RadarEntity>()

        @Volatile private var localPlayerX: Float = 0f
        @Volatile private var localPlayerY: Float = 0f

        fun addEntity(entity: RadarEntity)        { entityMap[entity.id] = entity }
        fun removeEntity(id: Int)                  { entityMap.remove(id) }
        fun clearAll()                             { entityMap.clear() }
        fun getEntities(): Collection<RadarEntity> = entityMap.values
        fun getLocalPlayerX(): Float               = localPlayerX
        fun getLocalPlayerY(): Float               = localPlayerY

        fun updatePosition(id: Int, x: Float, y: Float) {
            entityMap[id]?.let { entityMap[id] = it.copy(worldX = x, worldY = y) }
        }

        fun setLocalPlayerPosition(x: Float, y: Float) {
            localPlayerX = x
            localPlayerY = y
        }
    }

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }

        if (!Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY }

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        setupOverlay()
        return START_STICKY
    }

    override fun onDestroy() {
        radarView?.let { windowManager?.removeView(it) }
        radarView?.stopRendering()
        super.onDestroy()
    }

    private fun setupOverlay() {
        val view = RadarSurfaceView(this)
        radarView = view

        val params = WindowManager.LayoutParams(
            400, 400,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).also {
            it.gravity = Gravity.TOP or Gravity.START
            it.x = 0
            it.y = 100
        }

        windowManager?.addView(view, params)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID, "Dex Radar Overlay",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification() = NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
        .setContentTitle("Dex Radar Overlay")
        .setContentText("Overlay is active")
        .setSmallIcon(R.drawable.ic_radar_notif)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setOngoing(true)
        .setSilent(true)
        .build()
}
