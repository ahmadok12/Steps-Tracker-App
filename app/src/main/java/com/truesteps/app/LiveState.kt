package com.truesteps.app

/** In-memory status shared between the service, receivers and the screen (same process). */
object LiveState {
    @Volatile var serviceRunning = false
    @Volatile var motion: MotionReading? = null
    @Volatile var gpsActive = false
    @Volatile var lastSpeedKmh: Float? = null
    @Volatile var lastSpeedTimeMs = 0L
    @Volatile var pendingSteps = 0
    @Volatile var heldSteps = 0
    @Volatile var lastVehicleMs = 0L
    @Volatile var quietHours = false
}
