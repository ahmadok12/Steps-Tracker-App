package com.truesteps.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts tracking after the phone reboots or the app is updated. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Prefs.isTrackingEnabled(context) && Permissions.hasActivityRecognition(context)) {
            StepTrackingService.start(context)
        }
    }
}
