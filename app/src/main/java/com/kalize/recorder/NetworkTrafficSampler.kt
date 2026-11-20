package com.kalize.recorder

import android.content.Context
import android.net.TrafficStats
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Opt-in foreground-only traffic sampler. It measures aggregate device traffic,
 * does not create network requests, and persists the latest Mbps sample for Device Lab.
 */
object NetworkTrafficSampler {
    private const val PREFS = "kalize_settings"
    private const val KEY_ENABLED = "network_monitor_enabled"
    private const val KEY_DOWN = "network_down_mbps"
    private const val KEY_UP = "network_up_mbps"
    private const val KEY_AVAILABLE = "network_speed_available"
    private const val SAMPLE_INTERVAL_MS = 1_000L

    private val handler = Handler(Looper.getMainLooper())
    private var clients = 0
    private var appContext: Context? = null
    private var previousRx = TrafficStats.getTotalRxBytes()
    private var previousTx = TrafficStats.getTotalTxBytes()
    private var previousTime = SystemClock.elapsedRealtimeNanos()

    private val sample = object : Runnable {
        override fun run() {
            val context = appContext ?: return
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (clients <= 0 || !prefs.getBoolean(KEY_ENABLED, false)) return

            val rx = TrafficStats.getTotalRxBytes()
            val tx = TrafficStats.getTotalTxBytes()
            val now = SystemClock.elapsedRealtimeNanos()
            val elapsed = (now - previousTime).coerceAtLeast(1L) / 1_000_000_000.0
            val available = rx >= 0L && tx >= 0L && previousRx >= 0L && previousTx >= 0L
            if (available) {
                val down = ((rx - previousRx).coerceAtLeast(0L) * 8.0 / elapsed / 1_000_000.0)
                val up = ((tx - previousTx).coerceAtLeast(0L) * 8.0 / elapsed / 1_000_000.0)
                prefs.edit()
                    .putFloat(KEY_DOWN, down.toFloat())
                    .putFloat(KEY_UP, up.toFloat())
                    .putBoolean(KEY_AVAILABLE, true)
                    .putLong("network_sample_time", System.currentTimeMillis())
                    .apply()
            } else {
                prefs.edit().putBoolean(KEY_AVAILABLE, false).apply()
            }
            previousRx = rx
            previousTx = tx
            previousTime = now
            handler.postDelayed(this, SAMPLE_INTERVAL_MS)
        }
    }

    @Synchronized
    fun acquire(context: Context) {
        val app = context.applicationContext
        appContext = app
        if (!app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)) return
        clients++
        if (clients == 1) {
            previousRx = TrafficStats.getTotalRxBytes()
            previousTx = TrafficStats.getTotalTxBytes()
            previousTime = SystemClock.elapsedRealtimeNanos()
            handler.removeCallbacks(sample)
            handler.postDelayed(sample, SAMPLE_INTERVAL_MS)
        }
    }

    @Synchronized
    fun release(context: Context) {
        if (clients > 0) clients--
        if (clients == 0) {
            handler.removeCallbacks(sample)
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_AVAILABLE, false).apply()
        }
    }
}
