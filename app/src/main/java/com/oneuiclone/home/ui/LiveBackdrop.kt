package com.oneuiclone.home.ui

import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.view.View

/**
 * Live glass: draws the views behind a bar or card (wallpaper, background tint, scrolling apps), then blurs,
 * bends them at the edges and boosts saturation. [layers] are the views to capture, bottom to top.
 */
class LiveBackdrop(private val layers: () -> List<View>) {
    private val node = RenderNode("liveBackdrop")
    private val lens = RuntimeShader(LENS_AGSL)
    private val shape = Path()
    private val at = IntArray(2)
    private val layerAt = IntArray(2)
    private var effect: RenderEffect? = null
    private var key = ""

    /** Returns false when it can't draw (software canvas, no size), so the caller can fall back. */
    fun draw(
        canvas: Canvas, view: View, rect: RectF, radius: Float,
        blurPx: Float, amountPx: Float, depthPx: Float, clip: Path? = null,
    ): Boolean {
        if (!canvas.isHardwareAccelerated || view.width == 0 || view.height == 0 || rect.isEmpty) return false
        val w = view.width
        val h = view.height
        val pad = Math.ceil((amountPx + blurPx * 2f + 2f).toDouble()).toInt()
        view.getLocationInWindow(at)

        val k = "${rect.left},${rect.top},${rect.width()},${rect.height()},$radius,$blurPx,$amountPx,$depthPx,$pad"
        if (k != key) {
            key = k
            lens.setFloatUniform("origin", pad + rect.left, pad + rect.top)
            lens.setFloatUniform("size", rect.width(), rect.height())
            lens.setFloatUniform("radius", minOf(radius, rect.width() / 2f, rect.height() / 2f))
            lens.setFloatUniform("amount", amountPx)
            lens.setFloatUniform("depth", depthPx)
            var e = RenderEffect.createRuntimeShaderEffect(lens, "content")
            if (blurPx >= 1f) {
                e = RenderEffect.createChainEffect(e, RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP))
            }
            effect = RenderEffect.createColorFilterEffect(YunoGlass.saturation, e)
        }

        node.setPosition(-pad, -pad, w + pad, h + pad)
        node.setRenderEffect(effect)
        val rc = node.beginRecording()
        try {
            rc.translate((pad - at[0]).toFloat(), (pad - at[1]).toFloat())   // record in window coordinates
            rc.clipRect((at[0] - pad).toFloat(), (at[1] - pad).toFloat(), (at[0] + w + pad).toFloat(), (at[1] + h + pad).toFloat())
            for (v in layers()) {
                if (v.width == 0 || v.height == 0 || v.visibility != View.VISIBLE) continue
                v.getLocationInWindow(layerAt)
                rc.save()
                rc.translate(layerAt[0].toFloat(), layerAt[1].toFloat())
                rc.translate(-v.scrollX.toFloat(), -v.scrollY.toFloat())
                v.draw(rc)
                rc.restore()
            }
        } finally {
            node.endRecording()
        }

        val path = clip ?: shape.also { it.reset(); it.addRoundRect(rect, radius, radius, Path.Direction.CW) }
        canvas.save()
        canvas.clipPath(path)
        canvas.drawRenderNode(node)
        canvas.restore()
        return true
    }

    private companion object {
        const val LENS_AGSL = """
            uniform shader content;
            uniform float2 origin;
            uniform float2 size;
            uniform float radius;
            uniform float amount;
            uniform float depth;

            float sdRoundedRect(float2 p, float2 halfSize, float r) {
                float2 q = abs(p) - (halfSize - float2(r));
                return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
            }
            float2 gradSdRoundedRect(float2 coord, float2 halfSize, float r) {
                float2 cornerCoord = abs(coord) - (halfSize - float2(r));
                if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {
                    return sign(coord) * normalize(max(cornerCoord, 0.0) + 0.00001);
                } else {
                    float gradX = step(cornerCoord.y, cornerCoord.x);
                    return sign(coord) * float2(gradX, 1.0 - gradX);
                }
            }
            half4 main(float2 coord) {
                float2 halfSize = size * 0.5;
                float2 p = coord - (origin + halfSize);
                float inside = -sdRoundedRect(p, halfSize, radius);
                float t = clamp(1.0 - inside / depth, 0.0, 1.0);
                float2 n = gradSdRoundedRect(p, halfSize, radius);
                return content.eval(coord + n * (t * t * amount));
            }
        """
    }
}
