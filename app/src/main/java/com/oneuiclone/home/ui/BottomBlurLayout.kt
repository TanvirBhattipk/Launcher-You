package com.oneuiclone.home.ui

import android.content.Context
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.widget.LinearLayout
import com.oneuiclone.home.device.DeviceProfile
import kotlin.math.roundToInt

/**
 * A vertical LinearLayout whose bottom part gets a progressive blur: no blur at [setBlur]'s startY, growing to
 * MAX_BLUR_DP over [rampPx] and staying there to the bottom edge. Used under the search bar while the keyboard is
 * up, so the cards that run behind the bar melt away instead of being cut off.
 */
class BottomBlurLayout(context: Context, private val dp: DeviceProfile, private val rampPx: Float) : LinearLayout(context) {
    private val node = RenderNode("bottomProgressiveBlur")
    private var effect: RenderEffect? = null
    private var effectStep = -1
    private var startY = Float.MAX_VALUE
    private var step = 0

    /** [startY]: view-relative y where the blur starts (0 blur). [step]: 0..STEPS, eases the whole effect in/out. */
    fun setBlur(startY: Float, step: Int) {
        if (startY == this.startY && step == this.step) return
        this.startY = startY
        this.step = step
        invalidate()
    }

    private fun progressiveBlur(step: Int): RenderEffect {
        val k = step / STEPS.toFloat()
        val rMax = dp.dp(LEVELS_DP.last()) * k
        val n = LEVELS_DP.size
        var acc: RenderEffect? = null
        for (i in 0 until n) {
            val cur = dp.dp(LEVELS_DP[i]) * k
            val prev = if (i > 0) dp.dp(LEVELS_DP[i - 1]) * k else cur
            val next = if (i < n - 1) dp.dp(LEVELS_DP[i + 1]) * k else cur
            val shader = RuntimeShader(WEIGHT_AGSL).apply {
                setFloatUniform("levels", prev, cur, next)
                setFloatUniform("rMax", rMax)
                setFloatUniform("ramp", rampPx)
            }
            var e = RenderEffect.createRuntimeShaderEffect(shader, "content")
            if (cur >= 1f) e = RenderEffect.createChainEffect(e, RenderEffect.createBlurEffect(cur, cur, Shader.TileMode.MIRROR))
            acc = if (acc == null) e else RenderEffect.createBlendModeEffect(acc, e, BlendMode.PLUS)
        }
        return acc!!
    }

    override fun dispatchDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (step == 0 || startY >= h - 1f || w == 0f || !canvas.isHardwareAccelerated) { super.dispatchDraw(canvas); return }
        val y0 = startY.coerceAtLeast(0f)
        val band = h - y0

        // 1) everything above the blur, untouched
        canvas.save()
        canvas.clipRect(0f, 0f, w, y0)
        super.dispatchDraw(canvas)
        canvas.restore()

        // 2) the band: blur grows from 0 at its top edge
        if (step != effectStep) { effect = progressiveBlur(step); effectStep = step }
        node.setPosition(0, 0, width, band.roundToInt())
        node.setRenderEffect(effect)
        val rc = node.beginRecording()
        try {
            rc.translate(0f, -y0)
            rc.clipRect(0f, y0, w, h)
            super.dispatchDraw(rc)
        } finally {
            node.endRecording()
        }
        canvas.save()
        canvas.translate(0f, y0)
        canvas.drawRenderNode(node)
        canvas.restore()
    }

    companion object {
        const val STEPS = 12
        val LEVELS_DP = floatArrayOf(0f, 8f, 20f, 40f, 64f)     // blurred copies that get cross-faded (last = max)

        // Weight of one blurred copy: the wanted radius grows from 0 at y=0 to rMax over `ramp` px; each copy fades
        // in/out linearly around its own radius (levels = previous, this, next radius).
        const val WEIGHT_AGSL = """
            uniform shader content;
            uniform float3 levels;
            uniform float rMax;
            uniform float ramp;
            half4 main(float2 coord) {
                float r = rMax * smoothstep(0.0, ramp, coord.y);
                float w;
                if (r < levels.y) {
                    w = (levels.y > levels.x) ? clamp((r - levels.x) / (levels.y - levels.x), 0.0, 1.0) : 1.0;
                } else {
                    w = (levels.z > levels.y) ? clamp((levels.z - r) / (levels.z - levels.y), 0.0, 1.0) : 1.0;
                }
                return content.eval(coord) * half(w);
            }
        """
    }
}
