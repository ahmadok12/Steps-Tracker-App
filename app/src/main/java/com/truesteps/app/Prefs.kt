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
