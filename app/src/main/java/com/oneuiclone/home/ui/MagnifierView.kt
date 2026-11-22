package com.oneuiclone.home.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

class MagnifierView(context: Context, color: Int) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; this.color = color
    }
    var color: Int
        get() = p.color
        set(v) { if (p.color != v) { p.color = v; invalidate() } }

    override fun onDraw(canvas: Canvas) = draw(canvas, 0f, 0f, width.toFloat(), p)

    companion object {
        fun draw(canvas: Canvas, left: Float, top: Float, s: Float, p: Paint) {
            p.strokeWidth = s * 0.085f
            val cr = s * 0.30f; val cx = left + s * 0.42f; val cy = top + s * 0.42f
            canvas.drawCircle(cx, cy, cr, p)
            canvas.drawLine(cx + cr * 0.72f, cy + cr * 0.72f, left + s * 0.86f, top + s * 0.86f, p)
        }
    }
}
