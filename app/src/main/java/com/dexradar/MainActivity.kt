package com.dexradar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.dexradar.overlay.RadarOverlayService
import com.dexradar.vpn.AlbionVpnService

class MainActivity : AppCompatActivity() {

    private val VPN_REQUEST_CODE = 1001
    private val OVERLAY_REQUEST_CODE = 1002

    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var tvStatus: TextView

    private val vpnStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val running = intent.getBooleanExtra("running", false)
            updateStatusUI(running)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        btnStart = findViewById(R.id.btnStart)
        btnStop  = findViewById(R.id.btnStop)
        tvStatus = findViewById(R.id.tvStatus)

        btnStart.setOnClickListener { checkPermissionsAndStart() }
        btnStop.setOnClickListener  { stopRadar() }

        LocalBroadcastManager.getInstance(this)
            .registerReceiver(vpnStatusReceiver, IntentFilter("com.dexradar.VPN_STATUS"))
    }

    override fun onDestroy() {
        super.onDestroy()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(vpnStatusReceiver)
    }

    // STEP 1: Check overlay permission
    private fun checkPermissionsAndStart() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, OVERLAY_REQUEST_CODE)
            return
        }
        checkVpnPermission()
    }

    // STEP 2: Check VPN permission
    private fun checkVpnPermission() {
        val intent = VpnService.prepare(this)
        if (intent != null) startActivityForResult(intent, VPN_REQUEST_CODE)
        else startRadar()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            OVERLAY_REQUEST_CODE -> {
                if (Settings.canDrawOverlays(this)) checkVpnPermission()
                else Toast.makeText(this, "Overlay permission required", Toast.LENGTH_SHORT).show()
            }
            VPN_REQUEST_CODE -> {
                if (resultCode == RESULT_OK) startRadar()
                else Toast.makeText(this, "VPN permission required", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // STEP 3: Both granted — start both services
    private fun startRadar() {
        startService(Intent(this, AlbionVpnService::class.java))
        startService(Intent(this, RadarOverlayService::class.java))
        updateStatusUI(true)
    }

    private fun stopRadar() {
        stopService(Intent(this, AlbionVpnService::class.java))
        stopService(Intent(this, RadarOverlayService::class.java))
        updateStatusUI(false)
    }

    private fun updateStatusUI(running: Boolean) {
        tvStatus.text      = if (running) "Radar Active" else "Radar Stopped"
        btnStart.isEnabled = !running
        btnStop.isEnabled  = running
    }
}
