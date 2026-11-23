package com.oneuiclone.home.ui

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.widget.ScrollView
import com.oneuiclone.home.device.DeviceProfile
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sqrt
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader

/**
 * The Apps screen's list, with its own stretch overscroll at the bottom (drawn here, not by the system's EdgeEffect,
 * so a tap is never swallowed by a stretch that has only just settled): the list stretches when you pull past the
 * end, and when a fling arrives there. There is no stretch at the top: a swipe down that starts with the list at its
 * top drags the whole Apps screen closed under the finger (Launcher), and a fling that comes back up just stops.
 * The stretch scales the list away from the edge that is pulled, and springs back when the finger lets go.
 */
@SuppressLint("ViewConstructor")
class DrawerScrollView(context: Context, private val dp: DeviceProfile) : ScrollView(context) {

    private val maxStretch = 0.30f
    private val reach = dp.dp(160f)                             // pull (px) at which most of the stretch has been reached
    private val maxPull = dp.dp(220f)
    private val stiffness = 200f
    private val damping = 2f * 0.8f * sqrt(stiffness)

    /** < 0: pulled up at the bottom (px of finger travel). */
    private var pull = 0f
    private var vel = 0f
    private var touching = false
    private var running = false
    private var last = 0L
    private val tick = Runnable { step() }

    /** Called once per gesture when the list is pulled down past its top: the Apps screen closes. */
    var onPullToClose: (() -> Unit)? = null
    private var topPull = 0f
    private var closed = false
   // Progressive alpha-masked blur under the search bar (from the Glass app's shader).
    // Fully blurred above blurFullY, fading back to sharp by blurEndY (the top of the first row).
    private val blurFullY = dp.searchBarTop + dp.searchBarHeight * 0.5f
    private val blurEndY = maxOf(dp.appsGrid.top.toFloat(), blurFullY + dp.dp(24f))
    private val blurNode = RenderNode("drawerProgressiveBlur")
    private val maskShader = RuntimeShader(MASK_AGSL).apply { setFloatUniform("range", blurFullY, blurEndY) }
    private val maskPaint = Paint().apply {
        shader = maskShader
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    }
    private val blurEffect: RenderEffect by lazy {
        val shader = RuntimeShader(BLUR_AGSL).apply { setFloatUniform("range", blurFullY, blurEndY) }
        val r = dp.dp(12f)                       // blur radius
        RenderEffect.createChainEffect(
            RenderEffect.createRuntimeShaderEffect(shader, "content"),   // alpha mask, applied after the blur
            RenderEffect.createBlurEffect(r, r, Shader.TileMode.MIRROR),
        )
    } 
    init {
        overScrollMode = OVER_SCROLL_NEVER                      // the stretch is drawn below
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { touching = true; topPull = 0f; closed = false }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { touching = false; start() }   // let the stretch spring back
        }
        return super.onTouchEvent(ev)
    }

    override fun overScrollBy(
        deltaX: Int, deltaY: Int, scrollX: Int, scrollY: Int, scrollRangeX: Int, scrollRangeY: Int,
        maxOverScrollX: Int, maxOverScrollY: Int, isTouchEvent: Boolean,
    ): Boolean {
        if (isTouchEvent) {
            // a pull down with the list already at its top closes the Apps screen
            if (scrollY <= 0 && deltaY < 0) {
                topPull -= deltaY
                if (!closed && topPull > dp.dp(48f)) { closed = true; onPullToClose?.invoke() }
            } else if (deltaY > 0) topPull = 0f
            // only the bottom stretches under the finger; at the top the list just stops
            if (scrollY >= scrollRangeY && (deltaY > 0 || pull < 0f)) pull = min(0f, pull - deltaY)
            pull = pull.coerceIn(-maxPull, 0f)
            if (pull != 0f) { vel = 0f; applyStretch() }
        } else {
            // a fling arriving at the bottom stretches it (arriving at the top it just stops)
            val over = scrollY + deltaY - scrollRangeY
            if (over > 0 && scrollRangeY > 0) {
                pull = (pull - over * 2.5f).coerceIn(-maxPull, 0f)
                vel = 0f
                applyStretch()
                start()
            }
        }
        return super.overScrollBy(deltaX, deltaY, scrollX, scrollY, scrollRangeX, scrollRangeY, maxOverScrollX, maxOverScrollY, isTouchEvent)
    }

    private fun start() {
        if (running || touching) return
        running = true
        last = android.view.animation.AnimationUtils.currentAnimationTimeMillis()
        postOnAnimation(tick)
    }

    private fun step() {
        if (touching) { running = false; return }
        val now = android.view.animation.AnimationUtils.currentAnimationTimeMillis()
        val dt = ((now - last).coerceIn(1L, 32L)) / 1000f
        last = now
        vel += (-stiffness * pull - damping * vel) * dt
        pull += vel * dt
        if (abs(pull) < 0.4f && abs(vel) < 10f) {
            pull = 0f; vel = 0f; running = false
            applyStretch()
            return
        }
        applyStretch()
        postOnAnimation(tick)
    }

    /** The list scaled away from the edge that is pulled (the top row stays at the top when pulled there). */
    private fun applyStretch() {
        val c = getChildAt(0) ?: return
        if (pull == 0f) { c.scaleY = 1f; return }
        val s = maxStretch * (1f - exp(-abs(pull) / reach))
        c.pivotY = if (pull > 0f) scrollY.toFloat() else (scrollY + height).toFloat()
        c.scaleY = 1f + s
    }
        override fun dispatchDraw(canvas: Canvas) {
        // At rest nothing is under the bar, so skip the effect.
        if (scrollY <= 0 || width == 0 || !canvas.isHardwareAccelerated) { super.dispatchDraw(canvas); return }
        val w = width.toFloat()
        val top = scrollY.toFloat()                 // inside dispatchDraw the canvas is in content coordinates
        val band = minOf(blurEndY, height.toFloat())

        // 1) everything below the band, untouched
        canvas.save()
        canvas.clipRect(0f, top + band, w, top + height)
        super.dispatchDraw(canvas)
        canvas.restore()

        // 2) inside the band, the sharp apps fade out toward the top
        maskShader.setFloatUniform("range", top + blurFullY, top + blurEndY)
        val layer = canvas.saveLayer(0f, top, w, top + band, null)
        super.dispatchDraw(canvas)
        canvas.drawRect(0f, top, w, top + band, maskPaint)
        canvas.restoreToCount(layer)

        // 3) the blurred copy fades in toward the top
        blurNode.setPosition(0, 0, width, band.toInt())
        blurNode.setRenderEffect(blurEffect)
        val rc = blurNode.beginRecording()
        try {
            rc.translate(0f, -top)                  // record in view coordinates so the shader's y matches the screen
            rc.clipRect(0f, top, w, top + band)
            super.dispatchDraw(rc)
        } finally {
            blurNode.endRecording()
        }
        canvas.save()
        canvas.translate(0f, top)
        canvas.drawRenderNode(blurNode)
        canvas.restore()
    }
    override fun onDetachedFromWindow() {
        removeCallbacks(tick); running = false
        super.onDetachedFromWindow()
    }
        private companion object {
        // Same alpha mask as the Glass app: 1 at the top, smoothly 0 lower down.
        const val BLUR_AGSL = """
            uniform shader content;
            uniform float2 range;
            half4 main(float2 coord) {
                float blurAlpha = 1.0 - smoothstep(range.x, range.y, coord.y);
                return content.eval(coord) * blurAlpha;
            }
        """
        // The same mask, used to fade the sharp copy out so the two cross-fade.
        const val MASK_AGSL = """
            uniform float2 range;
            half4 main(float2 coord) {
                float a = 1.0 - smoothstep(range.x, range.y, coord.y);
                return half4(0.0, 0.0, 0.0, a);
            }
        """
    }
}
