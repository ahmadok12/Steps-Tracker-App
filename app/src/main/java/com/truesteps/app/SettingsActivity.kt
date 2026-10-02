package com.truesteps.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.text.format.DateFormat
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat

class SettingsActivity : AppCompatActivity() {

    private lateinit var quietSwitch: MaterialSwitch
    private lateinit var quietTimes: View
    private lateinit var quietStartText: TextView
    private lateinit var quietEndText: TextView
    private lateinit var quietSummary: TextView
    private lateinit var goalValue: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_settings)
        applyInsets(this, findViewById(R.id.root), findViewById(R.id.content))

        quietSwitch = findViewById(R.id.quietSwitch)
        quietTimes = findViewById(R.id.quietTimes)
        quietStartText = findViewById(R.id.quietStartText)
        quietEndText = findViewById(R.id.quietEndText)
        quietSummary = findViewById(R.id.quietSummary)
        goalValue = findViewById(R.id.goalValue)

        findViewById<View>(R.id.backButton).setOnClickListener { finish() }

        quietSwitch.isChecked = Prefs.quietEnabled(this)
        quietSwitch.setOnCheckedChangeListener { _, on ->
            saveQuiet(on, Prefs.quietStart(this), Prefs.quietEnd(this))
        }
        findViewById<View>(R.id.quietStartTile).setOnClickListener {
            pickTime("Quiet hours start", Prefs.quietStart(this)) { m ->
                saveQuiet(Prefs.quietEnabled(this), m, Prefs.quietEnd(this))
            }
        }
        findViewById<View>(R.id.quietEndTile).setOnClickListener {
            pickTime("Quiet hours end", Prefs.quietEnd(this)) { m ->
                saveQuiet(Prefs.quietEnabled(this), Prefs.quietStart(this), m)
            }
        }
        findViewById<View>(R.id.goalRow).setOnClickListener {
            showGoalDialog(this) { render() }
        }
        findViewById<View>(R.id.batteryButton).setOnClickListener { openBatterySettings(this) }

        render()
    }

    private fun saveQuiet(on: Boolean, start: Int, end: Int) {
        Prefs.setQuiet(this, on, start, end)
        StepTrackingService.refreshSettings(this)
        render()
    }

    private fun render() {
        val on = Prefs.quietEnabled(this)
        val start = Prefs.quietStart(this)
        val end = Prefs.quietEnd(this)
        quietTimes.alpha = if (on) 1f else 0.4f
        quietStartText.text = QuietHours.format(start)
        quietEndText.text = QuietHours.format(end)
        quietSummary.text = when {
            !on -> ""
            start == end -> "Start and end are the same, so quiet hours never apply."
            Prefs.isQuietNow(this) -> "Quiet hours are active now."
            else -> "Active every day from ${QuietHours.format(start)} to ${QuietHours.format(end)}."
        }
        goalValue.text = "%,d".format(Prefs.goal(this))
    }

    private fun pickTime(title: String, currentMin: Int, onPicked: (Int) -> Unit) {
        val picker = MaterialTimePicker.Builder()
            .setTitleText(title)
            .setTimeFormat(if (DateFormat.is24HourFormat(this)) TimeFormat.CLOCK_24H else TimeFormat.CLOCK_12H)
            .setHour(currentMin / 60)
            .setMinute(currentMin % 60)
            .build()
        picker.addOnPositiveButtonClickListener { onPicked(picker.hour * 60 + picker.minute) }
        picker.show(supportFragmentManager, "time")
    }
}

// ---------------------------------------------------------------- shared helpers

/** Keeps content clear of the status bar / gesture bar (edge-to-edge on Android 15). */
fun applyInsets(activity: AppCompatActivity, root: View, content: View) {
    WindowCompat.getInsetsController(activity.window, activity.window.decorView).isAppearanceLightStatusBars = false
    val top = content.paddingTop
    val bottom = content.paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        content.setPadding(content.paddingLeft, top + bars.top, content.paddingRight, bottom + bars.bottom)
        insets
    }
}

fun showGoalDialog(context: Context, onSaved: () -> Unit) {
    val density = context.resources.displayMetrics.density
    val input = EditText(context).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        setText(Prefs.goal(context).toString())
        setSelection(text.length)
    }
    val box = FrameLayout(context).apply {
        val p = (24 * density).toInt()
        setPadding(p, (8 * density).toInt(), p, 0)
        addView(input)
    }
    MaterialAlertDialogBuilder(context)
        .setTitle("Daily step goal")
        .setView(box)
        .setPositiveButton("Save") { _, _ ->
            val g = input.text.toString().toIntOrNull()
            if (g != null && g in 500..100_000) {
                Prefs.setGoal(context, g)
                StepWidget.updateAll(context)
                onSaved()
            } else {
                Toast.makeText(context, "Enter a number between 500 and 100,000", Toast.LENGTH_LONG).show()
            }
        }
        .setNegativeButton("Cancel", null)
        .show()
}

fun openBatterySettings(context: Context) {
    try {
        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    } catch (e: Exception) {
        context.startActivity(Intent(Settings.ACTION_SETTINGS))
    }
    Toast.makeText(context, "Find TrueSteps and set it to \"Don't optimize\" / \"Unrestricted\"", Toast.LENGTH_LONG).show()
}
