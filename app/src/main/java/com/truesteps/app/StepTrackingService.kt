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
 * Always-on foreground service:
 *  - reads the hardware step counter
 *  - receives activity-recognition updates
 *  - switches GPS on only while steps are coming in (saves battery)
 *  - every 30 s hands the window to [StepFilter] and saves the result
 */
class StepTrackingService : Service(), SensorEventListener {

    companion object {
        private const val TAG = "TrueSteps"
        private const val CHANNEL_ID = "tracking"
        private const val NOTIFICATION_ID = 1
        private const val WINDOW_MS = 30_000L
        private const val GPS_IDLE_OFF_MS = 120_000L
        private const val MAX_GPS_ACCURACY_M = 40f

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, StepTrackingService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, StepTrackingService::class.java))
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

    private val speeds = mutableListOf<Pair<Long, Float>>() // (time, km/h)
    private var prevLocation: Location? = null
    private var gpsOn = false
    private var activityIntent: PendingIntent? = null

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
            sensorManager.registerListener(this, counter, SensorManager.SENSOR_DELAY_NORMAL)
        }
        startActivityUpdates()
        handler.postDelayed(tick, WINDOW_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        handler.removeCallbacks(tick)
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
        StepWidget.updateAll(this)
        super.onDestroy()
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
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_steps)
            .setColor(0xFFC6FF3D.toInt())
            .setContentTitle("${"%,d".format(today.walkSteps)} steps today")
            .setContentText("${"%,d".format(today.vehicleSteps)} vehicle steps removed")
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
        pendingSteps += n
        lastStepMs = System.currentTimeMillis()
        LiveState.pendingSteps = pendingSteps
        if (!gpsOn) startGps()
    }

    private fun bootCount(): Int =
        Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, -1)

    // ---------------------------------------------------------------- window

    private fun closeWindow() {
        val now = System.currentTimeMillis()
        val windowSpeeds = speeds.filter { it.first >= windowStartMs }.map { it.second }
        val input = WindowInput(
            startMs = windowStartMs,
            endMs = now,
            steps = pendingSteps,
            speedsKmh = windowSpeeds,
            motion = LiveState.motion,
        )
        val committed = filter.process(input)
        committed.forEach { db.insert(it) }

        pendingSteps = 0
        windowStartMs = now
        speeds.removeAll { now - it.first > WINDOW_MS * 2 }

        LiveState.pendingSteps = 0
        LiveState.heldSteps = filter.heldSteps
        LiveState.lastVehicleMs = filter.lastVehicleMs

        // Battery: GPS off when no steps for a while and nothing is waiting for a decision.
        if (gpsOn && now - lastStepMs > GPS_IDLE_OFF_MS && filter.heldSteps == 0) stopGps()

        if (committed.isNotEmpty()) updateNotification()
    }

    // ---------------------------------------------------------------- GPS

    @SuppressLint("MissingPermission")
    private fun startGps() {
        if (!Permissions.hasLocation(this)) return
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5_000L)
            .setMinUpdateIntervalMillis(3_000L)
            .build()
        try {
            fused.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
            gpsOn = true
            LiveState.gpsActive = true
        } catch (e: SecurityException) {
            Log.w(TAG, "GPS not allowed", e)
        }
    }

    private fun stopGps() {
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
    }

    // ---------------------------------------------------------------- activity recognition

    @SuppressLint("MissingPermission")
    private fun startActivityUpdates() {
        if (!Permissions.hasActivityRecognition(this)) return
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pi = PendingIntent.getBroadcast(this, 1, Intent(this, ActivityUpdatesReceiver::class.java), flags)
        try {
            ActivityRecognition.getClient(this).requestActivityUpdates(10_000L, pi)
            activityIntent = pi
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
    }
}
