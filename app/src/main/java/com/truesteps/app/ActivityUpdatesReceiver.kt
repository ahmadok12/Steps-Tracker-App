package com.truesteps.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.DetectedActivity

/** Receives Google activity-recognition results (every ~10 s while the service runs). */
class ActivityUpdatesReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!ActivityRecognitionResult.hasResult(intent)) return
        val result = ActivityRecognitionResult.extractResult(intent) ?: return

        var vehicle = 0
        var bicycle = 0
        var foot = 0
        var still = 0
        for (a in result.probableActivities) {
            when (a.type) {
                DetectedActivity.IN_VEHICLE -> vehicle = maxOf(vehicle, a.confidence)
                DetectedActivity.ON_BICYCLE -> bicycle = maxOf(bicycle, a.confidence)
                DetectedActivity.ON_FOOT, DetectedActivity.WALKING, DetectedActivity.RUNNING ->
                    foot = maxOf(foot, a.confidence)
                DetectedActivity.STILL -> still = maxOf(still, a.confidence)
            }
        }
        val best = listOf(
            Motion.VEHICLE to vehicle,
            Motion.BICYCLE to bicycle,
            Motion.ON_FOOT to foot,
            Motion.STILL to still,
        ).maxByOrNull { it.second }!!

        LiveState.motion = if (best.second == 0) {
            MotionReading(Motion.UNKNOWN, 0, System.currentTimeMillis())
        } else {
            MotionReading(best.first, best.second, System.currentTimeMillis())
        }
    }
}
