package com.example.cameraprofessional

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * CornerOverlayView — tap 4 corners (in order: top-left, top-right,
 * bottom-right, bottom-left) of the document in the photo below it.
 * Used by DocumentScannerActivity to drive the perspective-correction warp.
 */
class CornerOverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    val points = mutableListOf<Pair<Float, Float>>()
    private val dotPaint = Paint().apply { color = Color.parseColor("#00E676"); style = Paint.Style.FILL }
    private val linePaint = Paint().apply { color = Color.parseColor("#00E676"); style = Paint.Style.STROKE; strokeWidth = 4f }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN && points.size < 4) {
            points.add(event.x to event.y)
            invalidate()
        }
        return true
    }

    fun reset() {
        points.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        points.forEach { (x, y) -> canvas.drawCircle(x, y, 16f, dotPaint) }
        if (points.size > 1) {
            for (i in 0 until points.size - 1) {
                canvas.drawLine(points[i].first, points[i].second, points[i + 1].first, points[i + 1].second, linePaint)
            }
        }
        if (points.size == 4) {
            canvas.drawLine(points[3].first, points[3].second, points[0].first, points[0].second, linePaint)
        }
    }
}
