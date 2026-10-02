package com.truesteps.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object Prefs {
    private fun prefs(c: Context) = c.getSharedPreferences("truesteps", Context.MODE_PRIVATE)

    fun isTrackingEnabled(c: Context) = prefs(c).getBoolean("tracking", false)
    fun setTrackingEnabled(c: Context, on: Boolean) = prefs(c).edit().putBoolean("tracking", on).apply()

    fun goal(c: Context) = prefs(c).getInt("goal", 10_000)
    fun setGoal(c: Context, goal: Int) = prefs(c).edit().putInt("goal", goal).apply()

    // Quiet hours: minutes after midnight. Default 23:00 – 07:00, off.
    fun quietEnabled(c: Context) = prefs(c).getBoolean("quiet_on", false)
    fun quietStart(c: Context) = prefs(c).getInt("quiet_start", 23 * 60)
    fun quietEnd(c: Context) = prefs(c).getInt("quiet_end", 7 * 60)
    fun setQuiet(c: Context, on: Boolean, startMin: Int, endMin: Int) =
        prefs(c).edit().putBoolean("quiet_on", on).putInt("quiet_start", startMin).putInt("quiet_end", endMin).apply()

    fun isQuietNow(c: Context, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!quietEnabled(c)) return false
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = nowMs }
        val minute = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
        return QuietHours.contains(quietStart(c), quietEnd(c), minute)
    }

    /** Last raw step-counter value + boot it belongs to, so restarts don't lose steps. */
    fun lastCounter(c: Context): Pair<Float, Int> =
        prefs(c).getFloat("counter", -1f) to prefs(c).getInt("boot", -1)

    fun saveCounter(c: Context, value: Float, boot: Int) =
        prefs(c).edit().putFloat("counter", value).putInt("boot", boot).apply()
}

object Permissions {
    private fun granted(c: Context, p: String) =
        ContextCompat.checkSelfPermission(c, p) == PackageManager.PERMISSION_GRANTED

    fun hasActivityRecognition(c: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || granted(c, Manifest.permission.ACTIVITY_RECOGNITION)

    fun hasLocation(c: Context) =
        granted(c, Manifest.permission.ACCESS_FINE_LOCATION) || granted(c, Manifest.permission.ACCESS_COARSE_LOCATION)

    fun hasNotifications(c: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || granted(c, Manifest.permission.POST_NOTIFICATIONS)

    fun needed(): Array<String> {
        val list = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) list += Manifest.permission.ACTIVITY_RECOGNITION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) list += Manifest.permission.POST_NOTIFICATIONS
        return list.toTypedArray()
    }
}

object QuietHours {
    /** True if [minute] (0..1439) falls in [start, end). Handles ranges past midnight, e.g. 23:00–07:00. */
    fun contains(start: Int, end: Int, minute: Int): Boolean = when {
        start == end -> false
        start < end -> minute in start until end
        else -> minute >= start || minute < end
    }

    fun format(min: Int): String = "%02d:%02d".format(min / 60, min % 60)
}
