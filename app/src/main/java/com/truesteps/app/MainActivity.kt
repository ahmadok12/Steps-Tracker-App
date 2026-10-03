package com.truesteps.app

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.truesteps.app.ui.StepRingView
import com.truesteps.app.ui.WeekChartView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var db: StepDatabase
    private lateinit var dateText: TextView
    private lateinit var statusDot: ImageView
    private lateinit var statusPillText: TextView
    private lateinit var ring: StepRingView
    private lateinit var goalText: TextView
    private lateinit var todayPending: TextView
    private lateinit var tileRemoved: TextView
    private lateinit var tileDistance: TextView
    private lateinit var tileMinutes: TextView
    private lateinit var liveActivity: TextView
    private lateinit var liveGps: TextView
    private lateinit var liveExtra: TextView
    private lateinit var toggleButton: MaterialButton
    private lateinit var weekChart: WeekChartView
    private lateinit var weekTotal: TextView
    private lateinit var logList: LinearLayout

    private val handler = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 2_000)
        }
    }
    private var lastLogSignature = ""

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (Permissions.hasActivityRecognition(this)) {
                if (!Permissions.hasLocation(this)) {
                    toast("Without location, only Google's activity detection is used (less accurate on a motorbike).")
                }
                startTracking()
            } else {
                toast("\"Physical activity\" permission is required to count steps.")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        db = StepDatabase.get(this)
        applyInsets(this, findViewById(R.id.root), findViewById(R.id.content))

        dateText = findViewById(R.id.dateText)
        statusDot = findViewById(R.id.statusDot)
        statusPillText = findViewById(R.id.statusPillText)
        ring = findViewById(R.id.ring)
        goalText = findViewById(R.id.goalText)
        todayPending = findViewById(R.id.todayPending)
        tileRemoved = findViewById(R.id.tileRemoved)
        tileDistance = findViewById(R.id.tileDistance)
        tileMinutes = findViewById(R.id.tileMinutes)
        liveActivity = findViewById(R.id.liveActivity)
        liveGps = findViewById(R.id.liveGps)
        liveExtra = findViewById(R.id.liveExtra)
        toggleButton = findViewById(R.id.toggleButton)
        weekChart = findViewById(R.id.weekChart)
        weekTotal = findViewById(R.id.weekTotal)
        logList = findViewById(R.id.logList)

        toggleButton.setOnClickListener {
            if (LiveState.serviceRunning) {
                Prefs.setTrackingEnabled(this, false)
                StepTrackingService.stop(this)
            } else {
                permissionLauncher.launch(Permissions.needed())
            }
            handler.postDelayed({ refresh() }, 300)
        }

        findViewById<View>(R.id.batteryButton).setOnClickListener { openBatterySettings(this) }
        findViewById<View>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        goalText.setOnClickListener { showGoalDialog(this) { refresh() } }

        // Resume automatically if tracking was on (e.g. after the app was swiped away).
        if (Prefs.isTrackingEnabled(this) && !LiveState.serviceRunning && Permissions.hasActivityRecognition(this)) {
            startTracking()
        }
    }

    override fun onResume() {
        super.onResume()
        lastLogSignature = ""
        handler.post(refresher)
    }

    override fun onPause() {
        handler.removeCallbacks(refresher)
        super.onPause()
    }

    private fun startTracking() {
        Prefs.setTrackingEnabled(this, true)
        StepTrackingService.start(this)
        handler.postDelayed({ refresh() }, 500)
    }

    // ---------------------------------------------------------------- UI

    private fun refresh() {
        val now = System.currentTimeMillis()
        val dayKey = StepDatabase.dayKey(now)
        val today = db.dayTotal(dayKey)
        val goal = Prefs.goal(this)

        dateText.text = SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(Date(now))

        val pct = today.walkSteps * 100 / goal.coerceAtLeast(1)
        ring.setProgress(today.walkSteps, goal, if (pct >= 100) "GOAL REACHED 🎉" else "$pct% OF GOAL")
        goalText.text = "Goal %,d  ✎".format(goal)

        val waiting = LiveState.pendingSteps + LiveState.heldSteps
        todayPending.visibility = if (waiting > 0) View.VISIBLE else View.GONE
        todayPending.text = "+$waiting steps being checked…"

        tileRemoved.text = "%,d".format(today.vehicleSteps)
        tileDistance.text = "%.1f km".format(today.walkSteps * 0.76f / 1000f)
        val mins = db.walkMinutes(dayKey)
        tileMinutes.text = if (mins >= 60) "${mins / 60}h ${mins % 60}m" else "${mins}m"

        renderStatus(now)
        renderWeek(now, goal)
        renderLog()
    }

    private fun renderStatus(now: Long) {
        val running = LiveState.serviceRunning
        val quietNow = running && LiveState.quietHours
        statusPillText.text = when {
            quietNow -> "Quiet hours"
            running -> "Tracking"
            else -> "Off"
        }
        statusDot.imageTintList = ColorStateList.valueOf(
            color(if (quietNow) R.color.violet else if (running) R.color.lime else R.color.flame)
        )

        toggleButton.text = if (running) "Stop tracking" else "Start tracking"
        toggleButton.backgroundTintList = ColorStateList.valueOf(color(if (running) R.color.surface_high else R.color.lime))
        toggleButton.setTextColor(color(if (running) R.color.text_primary else R.color.on_lime))

        if (!running) {
            liveActivity.text = "🚶  Not tracking"
            liveGps.text = "📍  GPS off"
            liveExtra.visibility = View.GONE
            return
        }

        if (LiveState.quietHours) {
            liveActivity.setTextColor(color(R.color.text_primary))
            liveGps.setTextColor(color(R.color.text_primary))
            liveActivity.text = "🌙  Quiet hours"
            liveGps.text = "📍  GPS off"
            liveExtra.visibility = View.VISIBLE
            liveExtra.text = "Steps are still counted. GPS and activity detection resume at " +
                QuietHours.format(Prefs.quietEnd(this)) + "."
            return
        }

        val m = LiveState.motion
        liveActivity.text = if (m == null || now - m.timeMs > 120_000) {
            "⏳  Detecting…"
        } else when (m.motion) {
            Motion.VEHICLE -> "🏍  In vehicle ${m.confidence}%"
            Motion.BICYCLE -> "🚲  On bike ${m.confidence}%"
            Motion.ON_FOOT -> "🚶  Walking ${m.confidence}%"
            Motion.STILL -> "🧍  Still ${m.confidence}%"
            Motion.UNKNOWN -> "❔  Unknown"
        }
        val isVehicle = m != null && (m.motion == Motion.VEHICLE || m.motion == Motion.BICYCLE) && now - m.timeMs <= 120_000
        liveActivity.setTextColor(color(if (isVehicle) R.color.flame else R.color.text_primary))

        val speed = LiveState.lastSpeedKmh
        val recentSpeed = speed != null && now - LiveState.lastSpeedTimeMs <= 90_000
        liveGps.text = when {
            !LiveState.gpsActive && recentSpeed -> "📍  %.0f km/h".format(speed)
            !LiveState.gpsActive -> "📍  GPS standby"
            speed == null || now - LiveState.lastSpeedTimeMs > 90_000 -> "📍  Finding GPS…"
            else -> "📍  %.0f km/h".format(speed)
        }
        val fast = speed != null && speed >= 15f && now - LiveState.lastSpeedTimeMs <= 90_000
        liveGps.setTextColor(color(if (fast) R.color.flame else R.color.text_primary))

        val extras = mutableListOf<String>()
        val sinceRide = now - LiveState.lastVehicleMs
        if (LiveState.lastVehicleMs > 0 && sinceRide < 3_600_000) {
            extras += "Last ride detected ${sinceRide / 60_000} min ago"
        }
        if (!Permissions.hasLocation(this)) extras += "⚠ Location is off, so the speed check is disabled"
        liveExtra.visibility = if (extras.isEmpty()) View.GONE else View.VISIBLE
        liveExtra.text = extras.joinToString("\n")
    }

    private fun renderWeek(now: Long, goal: Int) {
        val days = db.recentDays(7, now).reversed() // oldest first
        val inFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val outFmt = SimpleDateFormat("EEE", Locale.getDefault())
        val bars = days.mapIndexed { i, d ->
            val label = inFmt.parse(d.day)?.let { outFmt.format(it) } ?: d.day
            WeekChartView.Bar(label, d.walkSteps, d.vehicleSteps, isToday = i == days.lastIndex)
        }
        weekChart.setData(bars, goal)
        weekTotal.text = "%,d".format(days.sumOf { it.walkSteps })
    }

    private fun renderLog() {
        val entries = db.recentLog(25)
        val signature = entries.joinToString(",") { "${it.id}${it.kind.name.first()}" }
        if (signature == lastLogSignature) return
        lastLogSignature = signature

        logList.removeAllViews()
        if (entries.isEmpty()) {
            logList.addView(TextView(this).apply {
                text = "Nothing yet. Go for a walk or a ride."
                setTextColor(color(R.color.text_secondary))
                setPadding(0, dp(8), 0, dp(8))
            })
            return
        }
        val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        val inflater = LayoutInflater.from(this)
        for (e in entries) {
            val walk = e.kind == Kind.WALK
            val row = inflater.inflate(R.layout.item_log, logList, false)
            row.findViewById<TextView>(R.id.badge).apply {
                text = if (walk) "✓" else "✕"
                setTextColor(color(if (walk) R.color.lime else R.color.flame))
            }
            row.findViewById<TextView>(R.id.title).text =
                if (walk) "%,d steps kept".format(e.steps) else "%,d steps removed".format(e.steps)
            row.findViewById<TextView>(R.id.reason).text = e.reason
            row.findViewById<TextView>(R.id.time).text = timeFmt.format(Date(e.startMs))
            row.setOnClickListener { confirmCorrection(e) }
            logList.addView(row)
        }
    }

    /** Lets you fix a wrong decision: kept ↔ removed. */
    private fun confirmCorrection(e: LogEntry) {
        val toKind = if (e.kind == Kind.WALK) Kind.VEHICLE else Kind.WALK
        val msg = if (toKind == Kind.VEHICLE) {
            "Remove these %,d steps? Use this if you were in a car or on a bike at that time."
        } else {
            "Keep these %,d steps? Use this if you were really walking at that time."
        }.format(e.steps)
        MaterialAlertDialogBuilder(this)
            .setTitle("Fix this entry")
            .setMessage(msg)
            .setPositiveButton(if (toKind == Kind.VEHICLE) "Remove steps" else "Keep steps") { _, _ ->
                db.setKind(e.id, toKind)
                StepWidget.updateAll(this)
                lastLogSignature = ""
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun color(res: Int) = ContextCompat.getColor(this, res)
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
