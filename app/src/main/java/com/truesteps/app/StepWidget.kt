package com.truesteps.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.RemoteViews
import com.truesteps.app.ui.RingPainter

/**
 * Home-screen widget.
 *  - 2x2 (small): progress ring with today's real steps
 *  - wider than ~3 cells: ring + removed steps, distance and tracking status
 * Refreshed by the tracking service whenever new steps are saved, and every 30 min by the system.
 */
class StepWidget : AppWidgetProvider() {

    companion object {
        private const val RING_PX = 360

        fun updateAll(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, StepWidget::class.java))
            for (id in ids) update(context, mgr, id)
        }

        private fun update(context: Context, mgr: AppWidgetManager, id: Int) {
            val options = mgr.getAppWidgetOptions(id)
            val minWidthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 110)
            val wide = minWidthDp >= 200

            val today = StepDatabase.get(context).dayTotal(StepDatabase.dayKey(System.currentTimeMillis()))
            val goal = Prefs.goal(context)
            val pct = today.walkSteps * 100 / goal.coerceAtLeast(1)
            val caption = if (pct >= 100) "GOAL!" else "$pct% OF GOAL"
            val ring = RingPainter.bitmap(RING_PX, today.walkSteps / goal.toFloat(), today.walkSteps, caption)

            val views = RemoteViews(
                context.packageName,
                if (wide) R.layout.widget_wide else R.layout.widget_small
            )
            views.setImageViewBitmap(R.id.widgetRing, ring)

            if (wide) {
                views.setTextViewText(R.id.widgetRemoved, "%,d".format(today.vehicleSteps))
                views.setTextViewText(R.id.widgetDistance, "%.1f km".format(today.walkSteps * 0.76f / 1000f))
                val running = LiveState.serviceRunning || Prefs.isTrackingEnabled(context)
                views.setTextViewText(R.id.widgetStatus, if (running) "● Tracking" else "○ Paused")
                views.setTextColor(
                    R.id.widgetStatus,
                    Color.parseColor(if (running) "#C6FF3D" else "#FF5A36")
                )
            }

            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            views.setOnClickPendingIntent(R.id.widgetRoot, open)
            mgr.updateAppWidget(id, views)
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) update(context, appWidgetManager, id)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle
    ) {
        update(context, appWidgetManager, appWidgetId)
    }
}
