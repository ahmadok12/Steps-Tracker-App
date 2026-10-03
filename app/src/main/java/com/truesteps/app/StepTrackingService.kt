package com.truesteps.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/**
 * Always-on foreground service, tuned for battery:
 *  - hardware step counter, batched (the phone's processor can sleep ~10 s at a time)
 *  - Google activity detection: every 30 s while moving, every 3 min while idle
 *  - GPS only when it's actually needed, as short bursts (a few fixes, then off)
 *  - quiet hours: only the step counter runs (no GPS, no activity detection)
 *  - every 30 s hands the window to [StepFilter] and saves the result
 */
class StepTrackingService : Service(), SensorEventListener {

    companion object {
        private const val TAG = "TrueSteps"
        private const val CHANNEL_ID = "tracking"
        private const val NOTIFICATION_ID = 1
        private const val WINDOW_MS = 30_000L
        private const val MAX_GPS_ACCURACY_M = 40f

        // Step sensor batching
        private const val STEP_BATCH_US = 10_000_000 // 10 s

        // Activity detection
        private const val ACTIVITY_MOVING_MS = 30_000L
        private const val ACTIVITY_IDLE_MS = 180_000L
        private const val IDLE_AFTER_MS = 120_000L

        // GPS bursts
        private const val GPS_TRIGGER_STEPS = 3           // steps in the last 30 s before GPS is considered
        private const val GPS_BURST_FIXES = 3             // good fixes per burst
        private const val GPS_BURST_MAX_MS = 40_000L      // give up a burst after this long
        private const val GPS_COOLDOWN_MS = 60_000L       // pause between bursts
        private const val GPS_NO_FIX_COOLDOWN_MS = 300_000L    // indoors (no fix): wait 5 min
        private const val GPS_STATIONARY_COOLDOWN_MS = 120_000L // not travelling: wait 2 min
        private const val SPEED_MEMORY_MS = 75_000L       // how long a speed reading is used for decisions

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, StepTrackingService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, StepTrackingService::class.java))
        }

        /** Ask a running service to re-read settings (e.g. quiet hours changed). */
        fun refreshSettings(context: Context) {
            if (LiveState.serviceRunning) {
                context.startService(
                    Intent(context, StepTrackingService::class.java).setAction("refresh")
                )
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var sensorManager: SensorManager
    private lateinit var db: StepDatabase
    private val filter = StepFilter()

    private var lastCounter = -1f
    private var pendingSteps = 0
    private var windowStartMs = 0L
    private var lastStepMs = 0L
    private val recentSteps = ArrayDeque<Pair<Long, Int>>() // (time, steps) for the last 30 s

    private val speeds = mutableListOf<Pair<Long, Float>>() // (time, km/h)
    private var prevLocation: Location? = null
    private var gpsOn = false
    private var gpsBurstStartMs = 0L
    private var gpsBurstFixes = 0
    private var gpsCooldownUntil = 0L

    private var activityIntent: PendingIntent? = null
    private var activityIntervalMs = 0L // 0 = not registered
    private var quiet = false

    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            for (loc in result.locations) onLocation(loc)
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            closeWindow()
            handler.postDelayed(this, WINDOW_MS)
        }
    }

    private val burstTimeout = Runnable { endGpsBurst() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        db = StepDatabase.get(this)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        createChannel()

        if (!goForeground()) {
            stopSelf()
            return
        }
        LiveState.serviceRunning = true
        StepWidget.updateAll(this)
        windowStartMs = System.currentTimeMillis()

        val counter = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        if (counter == null) {
            Log.e(TAG, "No step counter sensor on this phone")
        } else {
            // Batched delivery: the sensor hub stores steps and wakes us at most every ~10 s.
            sensorManager.registerListener(this, counter, SensorManager.SENSOR_DELAY_NORMAL, STEP_BATCH_US)
        }
        applyMode(System.currentTimeMillis())
        handler.postDelayed(tick, WINDOW_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "refresh" && LiveState.serviceRunning) {
            applyMode(System.currentTimeMillis())
            updateNotification()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        handler.removeCallbacks(burstTimeout)
        if (LiveState.serviceRunning) {
            closeWindow()
            filter.flushAll(System.currentTimeMillis()).forEach { db.insert(it) }
        }
        sensorManager.unregisterListener(this)
        // Stopped on purpose: don't count steps from the "off" period when restarted later.
        if (!Prefs.isTrackingEnabled(this)) Prefs.saveCounter(this, -1f, -1)
        stopGps()
        stopActivityUpdates()
        LiveState.serviceRunning = false
        LiveState.pendingSteps = 0
        LiveState.heldSteps = 0
        LiveState.quietHours = false
        StepWidget.updateAll(this)
        super.onDestroy()
    }

    // ---------------------------------------------------------------- modes

    /** Quiet hours on/off, and activity-detection rate (moving vs idle). */
    private fun applyMode(now: Long) {
        val q = Prefs.isQuietNow(this, now)
        if (q != quiet) {
            quiet = q
            LiveState.quietHours = q
            Log.i(TAG, "Quiet hours: $q")
        }
        if (quiet) {
            stopGps()
            stopActivityUpdates()
            LiveState.motion = null
            return
        }
        val moving = now - lastStepMs < IDLE_AFTER_MS
        setActivityInterval(if (moving) ACTIVITY_MOVING_MS else ACTIVITY_IDLE_MS)
    }

    // ---------------------------------------------------------------- foreground

    private fun goForeground(): Boolean {
        val withLocation = Permissions.hasLocation(this)
        val attempts = if (withLocation) listOf(true, false) else listOf(false)
        for (loc in attempts) {
            try {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), serviceTypes(loc))
                return true
            } catch (e: Exception) {
                // e.g. starting a location service right after boot is not allowed on some versions
                Log.w(TAG, "startForeground failed (location=$loc)", e)
            }
        }
        return false
    }

    private fun serviceTypes(withLocation: Boolean): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0
        var types = 0
        if (withLocation) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
        }
        return types
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Step tracking", NotificationManager.IMPORTANCE_LOW)
        channel.setShowBadge(false)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val today = db.dayTotal(StepDatabase.dayKey(System.currentTimeMillis()))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val text = buildString {
            append("%,d vehicle steps removed".format(today.vehicleSteps))
            if (quiet) append(" · Quiet hours")
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_steps)
            .setColor(0xFFC6FF3D.toInt())
            .setContentTitle("${"%,d".format(today.walkSteps)} steps today")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification() {
        StepWidget.updateAll(this)
        if (!Permissions.hasNotifications(this)) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    // ---------------------------------------------------------------- steps

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_STEP_COUNTER) return
        val value = event.values[0]
        val boot = bootCount()

        if (lastCounter < 0) {
            // First reading since the service started: pick up steps taken while it was off.
            val (saved, savedBoot) = Prefs.lastCounter(this)
            // We can't tell how they happened, so they are saved as-is and labelled in the log.
            val missed = (value - saved).toInt()
            if (saved >= 0 && savedBoot == boot && missed > 0) {
                val now = System.currentTimeMillis()
                db.insert(CommittedWindow(now, now, missed, Kind.WALK, "Taken while tracking was off (not checked)"))
                updateNotification()
            }
        } else if (value >= lastCounter) {
            addSteps((value - lastCounter).toInt())
        }
        // value < lastCounter means the counter reset (reboot) - just re-baseline.
        lastCounter = value
        Prefs.saveCounter(this, value, boot)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun addSteps(n: Int) {
        if (n <= 0) return
        val now = System.currentTimeMillis()
        val wasIdle = now - lastStepMs >= IDLE_AFTER_MS
        pendingSteps += n
        lastStepMs = now
        recentSteps.addLast(now to n)
        LiveState.pendingSteps = pendingSteps

        if (wasIdle && !quiet) setActivityInterval(ACTIVITY_MOVING_MS)
        maybeStartGpsBurst(now)
    }

    private fun stepsLast30s(now: Long): Int {
        while (recentSteps.isNotEmpty() && now - recentSteps.first().first > WINDOW_MS) recentSteps.removeFirst()
        return recentSteps.sumOf { it.second }
    }

    private fun bootCount(): Int =
        Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, -1)

    // ---------------------------------------------------------------- window

    private fun closeWindow() {
        val now = System.currentTimeMillis()
        // Prefer readings from this window; otherwise fall back to the last ~75 s,
        // so one GPS burst also covers the window after it.
        val inWindow = speeds.filter { it.first >= windowStartMs }.map { it.second }
        val windowSpeeds = inWindow.ifEmpty {
            speeds.filter { now - it.first <= SPEED_MEMORY_MS }.map { it.second }
        }
        val input = WindowInput(
            startMs = windowStartMs,
            endMs = now,
            steps = pendingSteps,
            speedsKmh = windowSpeeds,
            motion = if (quiet) null else LiveState.motion,
        )
        val committed = filter.process(input)
        committed.forEach { db.insert(it) }

        pendingSteps = 0
        windowStartMs = now
        speeds.removeAll { now - it.first > SPEED_MEMORY_MS }

        LiveState.pendingSteps = 0
        LiveState.heldSteps = filter.heldSteps
        LiveState.lastVehicleMs = filter.lastVehicleMs

        applyMode(now)
        // Undecided steps waiting? Try to settle them with a GPS reading.
        if (filter.heldSteps > 0) maybeStartGpsBurst(now, force = true)

        if (committed.isNotEmpty()) updateNotification()
    }

    // ---------------------------------------------------------------- GPS (bursts)

    /**
     * GPS is the expensive part, so it only runs when:
     *  - not in quiet hours, and location permission is granted
     *  - steps are coming in (3+ in 30 s), or steps are waiting for a decision
     *  - Google's activity detection isn't already sure on its own
     *  - the previous burst ended at least a minute ago
     */
    private fun maybeStartGpsBurst(now: Long, force: Boolean = false) {
        if (gpsOn || quiet || now < gpsCooldownUntil) return
        if (!Permissions.hasLocation(this)) return
        val recent = stepsLast30s(now)
        if (!force && recent < GPS_TRIGGER_STEPS) return
        if (activityIsConfident(now, recent)) return
        startGps(now)
    }

    /** True when activity detection alone is trustworthy enough to skip GPS. */
    private fun activityIsConfident(now: Long, stepsLast30s: Int): Boolean {
        val m = LiveState.motion ?: return false
        if (now - m.timeMs > 45_000) return false
        return when (m.motion) {
            Motion.VEHICLE, Motion.BICYCLE -> m.confidence >= 80
            // Walking: also require a normal walking rhythm (1.2–2.6 steps/s),
            // because engine vibration can sometimes look like walking.
            Motion.ON_FOOT -> m.confidence >= 85 && (stepsLast30s / 30f) in 1.2f..2.6f
            else -> false
        }
    }

    @SuppressLint("MissingPermission")
    private fun startGps(now: Long) {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3_000L)
            .setMinUpdateIntervalMillis(2_000L)
            .setMaxUpdateAgeMillis(0L)
            .build()
        try {
            fused.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
            gpsOn = true
            gpsBurstStartMs = now
            gpsBurstFixes = 0
            LiveState.gpsActive = true
            handler.postDelayed(burstTimeout, GPS_BURST_MAX_MS)
        } catch (e: SecurityException) {
            Log.w(TAG, "GPS not allowed", e)
        }
    }

    private fun endGpsBurst() {
        if (!gpsOn) return
        val fixes = gpsBurstFixes
        stopGps()
        val now = System.currentTimeMillis()
        val lastSpeed = speeds.lastOrNull()?.second
        // Save battery when GPS can't help: indoors (no fix) or clearly not travelling.
        val cooldown = when {
            fixes == 0 -> GPS_NO_FIX_COOLDOWN_MS
            lastSpeed != null && lastSpeed < 2f -> GPS_STATIONARY_COOLDOWN_MS
            else -> GPS_COOLDOWN_MS
        }
        gpsCooldownUntil = now + cooldown
    }

    private fun stopGps() {
        handler.removeCallbacks(burstTimeout)
        if (!gpsOn) return
        fused.removeLocationUpdates(locationCallback)
        gpsOn = false
        prevLocation = null
        LiveState.gpsActive = false
    }

    private fun onLocation(loc: Location) {
        if (!loc.hasAccuracy() || loc.accuracy > MAX_GPS_ACCURACY_M) return
        val kmh: Float? = when {
            loc.hasSpeed() -> loc.speed * 3.6f
            else -> prevLocation?.let { prev ->
                val dt = (loc.time - prev.time) / 1000f
                if (dt in 1f..30f) loc.distanceTo(prev) / dt * 3.6f else null
            }
        }
        prevLocation = loc
        if (kmh == null) return
        val now = System.currentTimeMillis()
        speeds += now to kmh
        LiveState.lastSpeedKmh = kmh
        LiveState.lastSpeedTimeMs = now

        gpsBurstFixes++
        if (gpsBurstFixes >= GPS_BURST_FIXES) endGpsBurst()
    }

    // ---------------------------------------------------------------- activity recognition

    private fun activityPendingIntent(): PendingIntent {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getBroadcast(this, 1, Intent(this, ActivityUpdatesReceiver::class.java), flags)
    }

    /** (Re)registers activity detection at the given rate; no-op if already at that rate. */
    @SuppressLint("MissingPermission")
    private fun setActivityInterval(intervalMs: Long) {
        if (intervalMs == activityIntervalMs) return
        if (!Permissions.hasActivityRecognition(this)) return
        val pi = activityIntent ?: activityPendingIntent()
        try {
            ActivityRecognition.getClient(this).requestActivityUpdates(intervalMs, pi)
            activityIntent = pi
            activityIntervalMs = intervalMs
            Log.i(TAG, "Activity detection every ${intervalMs / 1000}s")
        } catch (e: SecurityException) {
            Log.w(TAG, "Activity recognition not allowed", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopActivityUpdates() {
        val pi = activityIntent ?: return
        try {
            ActivityRecognition.getClient(this).removeActivityUpdates(pi)
        } catch (e: SecurityException) {
            Log.w(TAG, "removeActivityUpdates failed", e)
        }
        activityIntent = null
        activityIntervalMs = 0L
    }
}
