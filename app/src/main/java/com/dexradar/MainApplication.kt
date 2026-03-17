package com.dexradar

import android.content.Context
import androidx.multidex.MultiDex
import androidx.multidex.MultiDexApplication
import com.dexradar.data.IdMapRepository
import com.dexradar.logger.DiscoveryLogger

class MainApplication : MultiDexApplication() {

    companion object {
        lateinit var instance: MainApplication private set
        lateinit var idMapRepository: IdMapRepository private set
        lateinit var discoveryLogger: DiscoveryLogger private set
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        MultiDex.install(this)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Load id_map.json from assets before anything else
        idMapRepository = IdMapRepository(this)
        idMapRepository.load()

        // Initialize discovery logger
        discoveryLogger = DiscoveryLogger(this)

        // CRITICAL: Fix Bug 3 — playerDot must default to true on fresh install
        val prefs = getSharedPreferences("dexradar_prefs", Context.MODE_PRIVATE)
        if (!prefs.contains("playerDot")) {
            prefs.edit().putBoolean("playerDot", true).apply()
        }
    }
}
