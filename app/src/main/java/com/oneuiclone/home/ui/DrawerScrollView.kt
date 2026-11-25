package com.oneuiclone.home.ui

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.widget.ScrollView
import com.oneuiclone.home.device.DeviceProfile
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import android.graphics.BlendMode
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

    /** The sheet draws the live home screen behind it: redraw it whenever the sheet moves on screen. */
    var trackSheet = false
    private val sheetAt = android.graphics.Matrix()
    private val sheetDrawnAt = android.graphics.Matrix()
    private val sheetMoved = android.view.ViewTreeObserver.OnPreDrawListener {
        if (trackSheet && alpha > 0f && isShown) {
            sheetAt.reset(); transformMatrixToGlobal(sheetAt)
            if (sheetAt != sheetDrawnAt) invalidate()
        }
        true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnPreDrawListener(sheetMoved)
    }

    override fun draw(canvas: Canvas) {
        sheetDrawnAt.reset(); transformMatrixToGlobal(sheetDrawnAt)
        super.draw(canvas)
    }

    /** The progressive blur under the old search bar (off: the sheet drawer has none). */
    var progressiveBlurEnabled = false

    /** A tap that lands above the sheet (on the blurred wallpaper) closes the drawer. */
    var onTapOutside: (() -> Unit)? = null
    private var downY = 0f
    private val tapSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop

    /** Called once per gesture when the list is pulled down past its top: the Apps screen closes. */
    var onPullToClose: (() -> Unit)? = null
    private var topPull = 0f
    private var closed = false
    // Progressive blur under the search bar: blur radius is MAX_BLUR_DP at the very top of the screen and eases down
    // to 0 (sharp) at blurEndY, with no tint. Built from a few blurred copies of the list, cross-faded per pixel.
    private val blurEndY = dp.searchBarTop + dp.searchBarHeight * 0.95f        // blur reaches 0% at 95% of the search bar's height
        private val barBottomY = (dp.searchBarTop + dp.searchBarHeight).toFloat()
    private val fadeStartY = (barBottomY + dp.appsGrid.top) / 2f   // icons fully visible below this: the middle of the gap between the bar and the first row
    private val fadeEndY = 2f * barBottomY - fadeStartY            // icons fully gone from here up, so 50% lands exactly on the bar's bottom edge
    private val blurNode = RenderNode("drawerProgressiveBlur")
    private var blurEffect: RenderEffect? = null
    private var blurEffectStep = -1

    /** [step] (1..STEPS) scales the whole ramp, so the blur eases in over the first bit of scrolling. */
    private fun progressiveBlur(step: Int): RenderEffect {
        val k = step / STEPS.toFloat()
        val rMax = dp.dp(MAX_BLUR_DP) * k
        val n = LEVELS_DP.size
        var acc: RenderEffect? = null
        for (i in 0 until n) {
            val cur = dp.dp(LEVELS_DP[i]) * k
            val prev = if (i > 0) dp.dp(LEVELS_DP[i - 1]) * k else cur
            val next = if (i < n - 1) dp.dp(LEVELS_DP[i + 1]) * k else cur
            val shader = RuntimeShader(WEIGHT_AGSL).apply {
                setFloatUniform("levels", prev, cur, next)
                setFloatUniform("rMax", rMax)
                setFloatUniform("bandEnd", blurEndY)
                setFloatUniform("fade", fadeEndY, fadeStartY)
            }
            var e = RenderEffect.createRuntimeShaderEffect(shader, "content")
            if (cur >= 1f) e = RenderEffect.createChainEffect(e, RenderEffect.createBlurEffect(cur, cur, Shader.TileMode.MIRROR))
            acc = if (acc == null) e else RenderEffect.createBlendModeEffect(acc, e, BlendMode.PLUS)
        }
        return acc!!
    }
    init {
        overScrollMode = OVER_SCROLL_NEVER                      // the stretch is drawn below
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { touching = true; topPull = 0f; closed = false; downY = ev.y }
            MotionEvent.ACTION_UP -> {
                touching = false; start()
                if (downY < dp.sheetTop && ev.y < dp.sheetTop && abs(ev.y - downY) < tapSlop) onTapOutside?.invoke()
            }
            MotionEvent.ACTION_CANCEL -> { touching = false; start() }   // let the stretch spring back
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
        // the sheet drawer has no search bar for the list to blur under: it simply clips at the sheet's handle
        if (!progressiveBlurEnabled || scrollY <= 0 || width == 0 || !canvas.isHardwareAccelerated) { super.dispatchDraw(canvas); return }
        val strength = (scrollY / dp.dp(48f)).coerceIn(0f, 1f)     // eases the effect in as you start to scroll
        val step = (strength * STEPS).roundToInt()
        if (step == 0) { super.dispatchDraw(canvas); return }
        val w = width.toFloat()
        val top = scrollY.toFloat()
                val band = minOf(maxOf(blurEndY, fadeStartY), height.toFloat())

        // 1) everything below the band, untouched
        canvas.save()
        canvas.clipRect(0f, top + band, w, top + height)
        super.dispatchDraw(canvas)
        canvas.restore()

        // 2) the band: the list with a blur that is strongest at the top and 0 at the bottom
        if (step != blurEffectStep) { blurEffect = progressiveBlur(step); blurEffectStep = step }
        blurNode.setPosition(0, 0, width, band.toInt())
        blurNode.setRenderEffect(blurEffect)
        val rc = blurNode.beginRecording()
        try {
            rc.translate(0f, -top)
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
        viewTreeObserver.removeOnPreDrawListener(sheetMoved)
        removeCallbacks(tick); running = false
        super.onDetachedFromWindow()
    }
    private companion object {
        const val MAX_BLUR_DP = 100f                                   // blur radius at the very top
        val LEVELS_DP = floatArrayOf(0f, 8f, 20f, 40f, 70f, 100f)      // blurred copies that get cross-faded
        const val STEPS = 12                                           // ease-in steps (the effect is rebuilt per step)

        // Weight of one blurred copy at each pixel: the wanted radius falls from rMax at y=0 to 0 at bandEnd, and
        // each copy fades in/out linearly around its own radius (levels = previous, this, next radius).
        const val WEIGHT_AGSL = """
            uniform shader content;
            uniform float3 levels;
            uniform float rMax;
            uniform float bandEnd;
            uniform float2 fade;
            half4 main(float2 coord) {
                float r = rMax * (1.0 - smoothstep(0.0, bandEnd, coord.y));
                float w;
                if (r < levels.y) {
                    w = (levels.y > levels.x) ? clamp((r - levels.x) / (levels.y - levels.x), 0.0, 1.0) : 1.0;
                } else {
                    w = (levels.z > levels.y) ? clamp((levels.z - r) / (levels.z - levels.y), 0.0, 1.0) : 1.0;
                }
                // fully transparent down to the bar's top, fully visible at the end of the blur
                float a = smoothstep(fade.x, fade.y, coord.y);
                return content.eval(coord) * half(w) * half(a);
            }
        """
    }
}
