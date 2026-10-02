package com.truesteps.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View

/** Seven rounded bars, oldest on the left, today highlighted. Dashed line = daily goal. */
class WeekChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    data class Bar(val label: String, val value: Int, val removed: Int, val isToday: Boolean)

    private var bars: List<Bar> = emptyList()
    private var goal = 10_000

    private val density = resources.displayMetrics.density
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val removedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF5A36") }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#20232D") }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9AA0B0")
        textAlign = Paint.Align.CENTER
        textSize = 12 * density
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 11 * density
        typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
    }
    private val goalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#55C6FF3D")
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        pathEffect = DashPathEffect(floatArrayOf(6 * density, 6 * density), 0f)
    }
    private val rect = RectF()

    fun setData(newBars: List<Bar>, dailyGoal: Int) {
        bars = newBars
        goal = dailyGoal.coerceAtLeast(1)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (190 * density).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        if (bars.isEmpty()) return
        val top = 22 * density
        val bottom = height - 26 * density
        val chartH = bottom - top
        val maxV = maxOf(goal, bars.maxOf { it.value + it.removed }).toFloat()
        val slot = width / bars.size.toFloat()
        val barW = slot * 0.5f
        val r = barW / 2f

        // goal line
        val gy = bottom - chartH * (goal / maxV)
        canvas.drawLine(0f, gy, width.toFloat(), gy, goalPaint)

        bars.forEachIndexed { i, b ->
            val cx = slot * i + slot / 2f
            val left = cx - barW / 2f
            val right = cx + barW / 2f

            rect.set(left, top, right, bottom)
            canvas.drawRoundRect(rect, r, r, trackPaint)

            val walkH = chartH * (b.value / maxV)
            val remH = chartH * (b.removed / maxV)

            // removed (vehicle) steps stacked on top in flame colour
            if (b.removed > 0) {
                rect.set(left, bottom - walkH - remH, right, bottom)
                removedPaint.alpha = if (b.isToday) 255 else 140
                canvas.drawRoundRect(rect, r, r, removedPaint)
            }
            if (b.value > 0) {
                rect.set(left, (bottom - walkH).coerceAtMost(bottom - barW), right, bottom)
                barPaint.shader = if (b.isToday) {
                    LinearGradient(0f, rect.top, 0f, bottom,
                        Color.parseColor("#C6FF3D"), Color.parseColor("#2BE4FF"), Shader.TileMode.CLAMP)
                } else {
                    LinearGradient(0f, rect.top, 0f, bottom,
                        Color.parseColor("#8B6CFF"), Color.parseColor("#5A45C8"), Shader.TileMode.CLAMP)
                }
                canvas.drawRoundRect(rect, r, r, barPaint)
            }

            if (b.value > 0) {
                val txt = if (b.value >= 1000) "%.1fk".format(b.value / 1000f) else b.value.toString()
                val ty = (bottom - walkH - remH - 6 * density).coerceAtLeast(valuePaint.textSize)
                canvas.drawText(txt, cx, ty, valuePaint)
            }
            labelPaint.color = if (b.isToday) Color.parseColor("#C6FF3D") else Color.parseColor("#9AA0B0")
            canvas.drawText(b.label, cx, height - 6 * density, labelPaint)
        }
    }
}
