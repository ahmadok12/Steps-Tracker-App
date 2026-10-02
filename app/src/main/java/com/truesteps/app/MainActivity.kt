package com.truesteps.app

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var db: StepDatabase
    private lateinit var todaySteps: TextView
    private lateinit var todayRemoved: TextView
    private lateinit var todayPending: TextView
    private lateinit var statusText: TextView
    private lateinit var toggleButton: MaterialButton
    private lateinit var historyList: LinearLayout
    private lateinit var logList: LinearLayout

    private val handler = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 2_000)
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (Permissions.hasActivityRecognition(this)) {
                if (!Permissions.hasLocation(this)) {
                    Toast.makeText(
                        this,
                        "Without location, only Google's activity detection is used (less accurate on a motorbike).",
                        Toast.LENGTH_LONG
                    ).show()
                }
                startTracking()
            } else {
                Toast.makeText(
                    this,
                    "\"Physical activity\" permission is required to count steps.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        db = StepDatabase.get(this)

        todaySteps = findViewById(R.id.todaySteps)
        todayRemoved = findViewById(R.id.todayRemoved)
        todayPending = findViewById(R.id.todayPending)
        statusText = findViewById(R.id.statusText)
        toggleButton = findViewById(R.id.toggleButton)
        historyList = findViewById(R.id.historyList)
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

        findViewById<MaterialButton>(R.id.batteryButton).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
            Toast.makeText(this, "Find TrueSteps and set it to \"Don't optimize\" / \"Unrestricted\"", Toast.LENGTH_LONG).show()
        }

        // Resume automatically if tracking was on (e.g. after the app was swiped away).
        if (Prefs.isTrackingEnabled(this) && !LiveState.serviceRunning && Permissions.hasActivityRecognition(this)) {
            startTracking()
        }
    }

    override fun onResume() {
        super.onResume()
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
        val today = db.dayTotal(StepDatabase.dayKey(now))
        todaySteps.text = "%,d".format(today.walkSteps)
        todayRemoved.text = "%,d vehicle steps removed".format(today.vehicleSteps)

        val waiting = LiveState.pendingSteps + LiveState.heldSteps
        todayPending.text = if (waiting > 0) "$waiting steps being checked…" else ""

        toggleButton.text = if (LiveState.serviceRunning) "Stop tracking" else "Start tracking"
        statusText.text = statusLines(now)

        renderHistory(db.recentDays(7, now))
        renderLog(db.recentLog(25))
    }

    private fun statusLines(now: Long): String {
        if (!LiveState.serviceRunning) return "Tracking is off."
        val lines = mutableListOf<String>()

        val m = LiveState.motion
        lines += if (m == null || now - m.timeMs > 120_000) {
            "Activity: waiting for Google activity detection…"
        } else {
            val label = when (m.motion) {
                Motion.VEHICLE -> "In a vehicle"
                Motion.BICYCLE -> "On a bike"
                Motion.ON_FOOT -> "Walking"
                Motion.STILL -> "Still"
                Motion.UNKNOWN -> "Unknown"
            }
            "Activity: $label (${m.confidence}%)"
        }

        val speed = LiveState.lastSpeedKmh
        lines += when {
            !LiveState.gpsActive -> "GPS: off (turns on when you start moving)"
            speed == null || now - LiveState.lastSpeedTimeMs > 30_000 -> "GPS: on, waiting for a fix…"
            else -> "GPS: %.1f km/h".format(speed)
        }

        val sinceRide = now - LiveState.lastVehicleMs
        if (LiveState.lastVehicleMs > 0 && sinceRide < 3_600_000) {
            lines += "Last ride detected: ${sinceRide / 60_000} min ago"
        }
        if (!Permissions.hasLocation(this)) lines += "⚠ Location permission off: speed check disabled"
        return lines.joinToString("\n")
    }

    private fun renderHistory(days: List<DayTotal>) {
        historyList.removeAllViews()
        val maxSteps = days.maxOf { it.walkSteps }.coerceAtLeast(1)
        val inFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val outFmt = SimpleDateFormat("EEE d MMM", Locale.getDefault())

        days.forEachIndexed { i, d ->
            val label = if (i == 0) "Today" else inFmt.parse(d.day)?.let { outFmt.format(it) } ?: d.day

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            header.addView(TextView(this).apply {
                text = label
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            header.addView(TextView(this).apply {
                text = buildString {
                    append("%,d".format(d.walkSteps))
                    if (d.vehicleSteps > 0) append("  (−%,d)".format(d.vehicleSteps))
                }
                gravity = Gravity.END
            })
            row.addView(header)
            row.addView(LinearProgressIndicator(this).apply {
                max = maxSteps
                progress = d.walkSteps
                setIndicatorColor(ContextCompat.getColor(this@MainActivity, R.color.brand))
                trackCornerRadius = dp(3)
                trackThickness = dp(6)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(4) }
            })
            historyList.addView(row)
        }
    }

    private fun renderLog(entries: List<LogEntry>) {
        logList.removeAllViews()
        if (entries.isEmpty()) {
            logList.addView(TextView(this).apply { text = "Nothing yet — go for a walk or a ride." })
            return
        }
        val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        for (e in entries) {
            val walk = e.kind == Kind.WALK
            logList.addView(TextView(this).apply {
                text = "${timeFmt.format(Date(e.startMs))}  ${if (walk) "✓ kept" else "✗ removed"} ${e.steps} — ${e.reason}"
                setTextColor(ContextCompat.getColor(this@MainActivity, if (walk) R.color.brand else R.color.vehicle))
                setPadding(0, dp(3), 0, dp(3))
            })
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
