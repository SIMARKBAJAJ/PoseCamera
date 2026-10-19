package com.example.posecamera.power

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Call [start]/[stop] from the main thread. Every event just re-reads the full state. */
class PowerMonitor(context: Context) {
    private val appContext = context.applicationContext
    private val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val _state = MutableStateFlow(read())
    val state: StateFlow<PowerState> = _state

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            _state.value = read()
        }
    }
    private var thermalHook: ThermalHook? = null
    private var started = false

    fun start() {
        if (started) return
        started = true
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED).apply {
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        ContextCompat.registerReceiver(appContext, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            thermalHook = ThermalHook().also {
                powerManager.addThermalStatusListener(ContextCompat.getMainExecutor(appContext), it)
            }
        }
        _state.value = read()
    }

    fun stop() {
        if (!started) return
        started = false
        appContext.unregisterReceiver(receiver)
        thermalHook?.let { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) removeHook(it) }
        thermalHook = null
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun removeHook(hook: ThermalHook) = powerManager.removeThermalStatusListener(hook)

    @RequiresApi(Build.VERSION_CODES.Q)
    private inner class ThermalHook : PowerManager.OnThermalStatusChangedListener {
        override fun onThermalStatusChanged(status: Int) {
            _state.value = read()
        }
    }

    private fun read(): PowerState {
        val battery = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val tempTenths = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        val apiThermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) powerManager.currentThermalStatus else null
        return PowerState(
            batteryPercent = if (level >= 0 && scale > 0) level * 100 / scale else 100, // unknown -> don't degrade
            charging = plugged != 0 || status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL,
            batterySaver = powerManager.isPowerSaveMode,
            thermal = resolveThermal(apiThermal, tempTenths),
        )
    }
}

/** Remembers the user's answer per tier in SharedPreferences. */
class ConsentStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("power_consent", Context.MODE_PRIVATE)

    fun get(tier: Tier): Consent =
        Consent.entries.getOrElse(prefs.getInt(tier.name, 0)) { Consent.UNDECIDED }

    fun set(tier: Tier, consent: Consent) = prefs.edit().putInt(tier.name, consent.ordinal).apply()
}
