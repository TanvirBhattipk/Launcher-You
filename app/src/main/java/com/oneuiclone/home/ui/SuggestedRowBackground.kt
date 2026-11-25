package com.oneuiclone.home.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import com.oneuiclone.home.device.DeviceProfile

/**
 * The "All apps" header under the Apps screen's first row of suggested apps (Pixel launcher), separating it from the
 * list. The view is the suggested row's cell; the header is drawn just below it, centred in the gap the list leaves
 * there ([GAP_DP] tall, set in Launcher.bindApps).
 */
class SuggestedRowBackground(context: Context, private val dp: DeviceProfile) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = DrawerInk.header(context)
        textSize = dp.dp(14f)
        typeface = Fonts.ui(context, 500)
        textAlign = Paint.Align.CENTER
    }

    override fun onDraw(canvas: Canvas) {
        val fm = paint.fontMetrics
        val mid = height + dp.dp(GAP_DP) / 2f                   // outside the cell: the grid doesn't clip its children
        canvas.drawText("All apps", width / 2f, mid - (fm.ascent + fm.descent) / 2f, paint)
    }

    companion object {
        const val GAP_DP = 40f
    }
}
