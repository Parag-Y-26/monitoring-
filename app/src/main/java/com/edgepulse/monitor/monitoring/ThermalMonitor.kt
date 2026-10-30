package com.edgepulse.monitor.monitoring

import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat

class ThermalMonitor(context: Context, private val onStatus: (Int) -> Unit) {
    private val power = context.getSystemService(PowerManager::class.java)
    private val executor = ContextCompat.getMainExecutor(context)
    private val listener = if (Build.VERSION.SDK_INT >= 29) PowerManager.OnThermalStatusChangedListener(onStatus) else null
    fun start() { if (Build.VERSION.SDK_INT >= 29) { power.addThermalStatusListener(executor, listener!!); onStatus(power.currentThermalStatus) } }
    fun close() { if (Build.VERSION.SDK_INT >= 29) power.removeThermalStatusListener(listener!!) }
}
