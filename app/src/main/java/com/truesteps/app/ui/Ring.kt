package com.truesteps.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.animation.ValueAnimator
import kotlin.math.min

/**
 * Draws the progress ring (gradient arc + big step number). Used by the
 * main screen ([StepRingView]) and rendered to a bitmap for the home-screen widget.
 */
object RingPainter {
    val LIME = Color.parseColor("#C6FF3D")
    val CYAN = Color.parseColor("#2BE4FF")
    val TRACK = Color.parseColor("#262A35")
    val LABEL = Color.parseColor("#9AA0B0")

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = TRACK
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val number = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
    }
    private val caption = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = LABEL
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacing = 0.1f
    }
    private val oval = RectF()

    /**
     * @param fraction 0..1+ of goal reached (values above 1 draw a full ring)
     * @param caption line under the number, e.g. "OF 10,000"
     */
    fun draw(canvas: Canvas, size: Float, fraction: Float, steps: Int, captionText: String, showText: Boolean = true) {
        val stroke = size * 0.085f
        val pad = stroke / 2f + size * 0.04f
        oval.set(pad, pad, size - pad, size - pad)

        track.strokeWidth = stroke
        canvas.drawArc(oval, 0f, 360f, false, track)

        val f = fraction.coerceIn(0f, 1f)
        if (f > 0f) {
            val sweep = (360f * f).coerceAtLeast(2f)
            val cx = size / 2f
            arc.strokeWidth = stroke
            arc.shader = SweepGradient(cx, cx, intArrayOf(LIME, CYAN, LIME), floatArrayOf(0f, 0.5f, 1f))
            glow.strokeWidth = stroke * 1.9f
            glow.shader = arc.shader
            glow.alpha = 40

            canvas.save()
            canvas.rotate(-90f, cx, cx)
            canvas.drawArc(oval, 0f, sweep, false, glow)
            canvas.drawArc(oval, 0f, sweep, false, arc)
            canvas.restore()
        }

        if (showText) {
            val text = "%,d".format(steps)
            val maxWidth = size - 2 * pad - stroke * 2.2f
            number.textSize = size * 0.22f
            val w = number.measureText(text)
            if (w > maxWidth) number.textSize *= maxWidth / w
            val cy = size / 2f
            canvas.drawText(text, size / 2f, cy + number.textSize * 0.32f, number)
            caption.textSize = size * 0.058f
            canvas.drawText(captionText, size / 2f, cy + number.textSize * 0.32f + caption.textSize * 2f, caption)
        }
    }

    fun bitmap(sizePx: Int, fraction: Float, steps: Int, captionText: String, showText: Boolean = true): Bitmap {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        draw(Canvas(bmp), sizePx.toFloat(), fraction, steps, captionText, showText)
        return bmp
    }
}

class StepRingView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var shownFraction = 0f
    private var shownSteps = 0
    private var caption = ""
    private var animator: ValueAnimator? = null

    fun setProgress(steps: Int, goal: Int, captionText: String) {
        caption = captionText
        val target = steps / goal.coerceAtLeast(1).toFloat()
        if (steps == shownSteps && target == shownFraction && animator == null) {
            invalidate(); return
        }
        val fromF = shownFraction
        val fromS = shownSteps
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (fromS == 0) 900 else 400
            interpolator = DecelerateInterpolator(2f)
            addUpdateListener {
                val t = it.animatedValue as Float
                shownFraction = fromF + (target - fromF) * t
                shownSteps = (fromS + (steps - fromS) * t).toInt()
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    shownSteps = steps
                    shownFraction = target
                    animator = null
                    invalidate()
                }
            })
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, w)
    }

    override fun onDraw(canvas: Canvas) {
        val size = min(width, height).toFloat()
        canvas.save()
        canvas.translate((width - size) / 2f, (height - size) / 2f)
        RingPainter.draw(canvas, size, shownFraction, shownSteps, caption)
        canvas.restore()
    }
}
