package com.saridub.app

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import java.io.File

enum class Thermal { NORMAL, WARM, HOT, CRITICAL }

/** Maps Android's thermal status to our throttling levels. Pipeline duty-cycles/pauses on these. */
class ThermalMonitor(ctx: Context) {
    private val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
    fun level(): Thermal {
        if (Build.VERSION.SDK_INT < 29) return Thermal.NORMAL
        return when (pm.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE, PowerManager.THERMAL_STATUS_LIGHT -> Thermal.NORMAL
            PowerManager.THERMAL_STATUS_MODERATE -> Thermal.WARM
            PowerManager.THERMAL_STATUS_SEVERE -> Thermal.HOT
            else -> Thermal.CRITICAL
        }
    }
}

class MemoryGuard(private val ctx: Context) {
    private val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    fun info(): ActivityManager.MemoryInfo = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
    fun low(): Boolean = info().let { it.lowMemory || it.availMem < 150L * 1024 * 1024 }
}

object Storage {
    fun freeBytes(dir: File): Long = StatFs(dir.path).availableBytes

    /** Worst-case working space: raw decode (<=48k stereo) + 24k mono PCM + segment cache + chunk WAVs. */
    fun requiredBytes(durationMs: Long): Long {
        val sec = durationMs / 1000.0
        return (sec * (192_000 + 48_000 + 30_000 + 48_000)).toLong()
    }

    fun gb(b: Long) = "%.2f GB".format(b / 1073741824.0)
}

data class DeviceProfile(val ramMb: Long, val cores: Int, val abi: String, val sdk: Int, val freeBytes: Long, val recommended: String) {
    companion object {
        fun detect(ctx: Context): DeviceProfile {
            val ram = MemoryGuard(ctx).info().totalMem / 1048576
            val rec = when { ram < 3000 -> "FAST"; ram < 6000 -> "BALANCED"; else -> "HIGH" }
            return DeviceProfile(ram, Runtime.getRuntime().availableProcessors(), Build.SUPPORTED_ABIS.firstOrNull() ?: "?",
                Build.VERSION.SDK_INT, Storage.freeBytes(ctx.filesDir), rec)
        }
    }
}
