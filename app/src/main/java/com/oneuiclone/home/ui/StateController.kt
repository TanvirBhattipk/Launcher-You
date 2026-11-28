package com.oneuiclone.home.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.os.SystemClock
import android.view.View
import com.oneuiclone.home.motion.Easing
import kotlin.math.abs
import kotlin.math.max
import android.view.animation.PathInterpolator
import androidx.core.animation.doOnEnd
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce

/**
 * HOME ⇄ APPS: the wallpaper blurs while Home's icons and widgets stay as they are, and the drawer sheet (the suggested
 * apps and the list on it) rises from the bottom edge over Home, fading in as it leaves it. The drawer follows the finger exactly (one pixel for one pixel, never
 * ahead of it); when the finger lets go the rest of the way is finished from where it is. Back and swipe down run it
 * the other way. Search asks for a [goTo] with a
 * duration instead: that only crossfades Home into the Apps screen's background (and back), so the search page can
 * fade in over it.
 */
class StateController(
    private val home: View,
    private val apps: View,
    private val background: BackgroundController,
    @Suppress("UNUSED_PARAMETER") private val transitionY: () -> Float,
) {
    /** Where the sheet's top edge sits inside the Apps view (DeviceProfile.sheetTop). Lets the sheet's edge follow the finger 1:1. */
    var sheetTop: () -> Float = { 0f }

    enum class State { HOME, APPS }

    var state = State.HOME; private set
    var onStateChanged: ((State) -> Unit)? = null
    var onTransitionStart: ((toApps: Boolean) -> Unit)? = null

    private var anim: ValueAnimator? = null
    private var springAnim: SpringAnimation? = null
    /** How far the Apps screen is open: 0 = Home, 1 = Apps. */
    private var a = 0f
    /** Progress of the swipe in hand, 0…1 toward its target. */
    private var f = 0f
    private var toApps = true
    private var dragging = false
    /** The swipe in hand has begun to show (it wasn't just a quick flick that went nowhere). */
    private var visible = false
    private var dragStartMs = 0L
    private var tintAnim: ValueAnimator? = null
    private var tintDriven = false

    /** Closing: the wallpaper tint and Home's blur fade on their own timer, not on the sheet's spring. */
    private fun startCloseTint() {
        tintAnim?.cancel()
        val from = ramp(a.coerceIn(0f, 1f))
        tintDriven = true
        tintAnim = ValueAnimator.ofFloat(from, 0f).apply {
            duration = (CLOSE_TINT_MS * from).toLong().coerceAtLeast(60L)
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener {
                val r = it.animatedValue as Float
                background.set(BackgroundController.Kind.APPS, r)
                homeBlur(r)
            }
            start()
        }
    }

    private fun stopTint() {
        tintAnim?.cancel()
        tintAnim = null
        tintDriven = false
    }
val isTransitioning get() = anim?.isRunning == true || springAnim?.isRunning == true || dragging

    private fun prepare() {
        apps.visibility = View.VISIBLE
        home.visibility = View.VISIBLE
        home.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        apps.setLayerType(View.LAYER_TYPE_HARDWARE, null)
    }

/** A swipe starts. */
    fun begin(towardApps: Boolean) {
        stopTint()
        anim?.cancel()
        springAnim?.cancel()
        springRunning = false
        apps.removeCallbacks(springTick)
        toApps = towardApps
        dragging = true
        visible = false                                 // nothing shows until the finger has really travelled (REVEAL_DP)
        dragStartMs = SystemClock.uptimeMillis()
        f = 0f
    }

    /**
     * The finger moves: [progress] (0…1 of the swipe's distance) is what decides on release, while the screens follow
     * the finger itself, [distancePx] from where it went down, exactly.
     */
    fun drag(progress: Float, distancePx: Float) {
        f = progress.coerceIn(0f, 1f)
        val dead = home.resources.displayMetrics.density * REVEAL_DP
        if (!visible) {
            if (distancePx < dead) return               // a short, accidental swipe: nothing moves, nothing blurs
            visible = true
            prepare()
            onTransitionStart?.invoke(toApps)
        }
        // the first REVEAL_DP are eaten, then the gap closes smoothly so the sheet ends up exactly under the finger
        val lag = dead * (1f - smooth((distancePx - dead) / (2f * dead)))
        val shown = ((distancePx - lag) / travel()).coerceIn(0f, 1f)
        frame(if (toApps) shown else 1f - shown)
    }
    /**
     * The swipe is let go: it goes through when it was a fling or went far enough (a fling back cancels it); either
     * way the rest is animated from where it is.
     * @param velocity Samsung-px/ms, positive toward the target screen.
     */
    fun release(velocity: Float) {
        dragging = false
        // it takes a longer swipe than before and speed doesn't count: a quick flick that goes only a short way falls back
        val commit = f > COMMIT_AT && velocity > -1f
        f = 0f
        if (!visible && !commit) return                      // nothing was shown: nothing to undo
        val targetState = if (commit) (if (toApps) State.APPS else State.HOME) else state
        run(targetState, velocityPxPerSec = velocity * 1000f, fromDrag = true)
    }

    /** [duration] 0 or less: the drawer's own open/close; otherwise a crossfade over that long (search). */
    fun goTo(target: State, duration: Long = -1L) {
        if (target == state && anim?.isRunning != true && springAnim?.isRunning != true && !dragging) return
        if ((anim?.isRunning == true || springAnim?.isRunning == true) && (target == State.APPS) == toApps) return
        onTransitionStart?.invoke(target == State.APPS)
        if (duration <= 0L) run(target) else fade(target, duration)
    }

fun setImmediate(target: State) {
        stopTint()
        anim?.cancel()
        springAnim?.cancel()
        springRunning = false
        apps.removeCallbacks(springTick)
        toApps = target == State.APPS
        settle(target)
    }

    private fun smooth(x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** Distance the Apps view travels: the sheet's top edge goes from the screen's bottom edge to its resting place. */
    private fun travel() = (apps.height - sheetTop()).coerceAtLeast(1f)

    /** The blur / tint ramp: fast at the start (as on Pixel), full by [BLUR_FULL_AT] of the swipe. */
    private fun ramp(p: Float): Float {
        val x = (p / BLUR_FULL_AT).coerceIn(0f, 1f)
        return 1f - (1f - x) * (1f - x)
    }

    /** One frame of the open/close at [p] (0 Home ... 1 Apps). */
    private fun frame(p: Float) {
        a = p
        val r = ramp(p.coerceIn(0f, 1f))
        background.appsDimScale = 1f
        if (!tintDriven) background.set(BackgroundController.Kind.APPS, r)
        home.alpha = 1f
        if (!tintDriven) homeBlur(r)

        // the sheet's top edge is exactly under the finger: it starts at the screen's bottom edge, no gap, no lag
        if (p > 1f) {
            apps.translationY = 0f
            apps.pivotY = apps.height.toFloat()
            apps.scaleY = 1f + (p - 1f)
        } else {
            apps.translationY = travel() * (1f - p)
            apps.scaleY = 1f
        }
        apps.alpha = 1f
    }

    /** Home's icons and widgets: blurred and washed with the sheet's colour as the sheet rises. */
    private fun homeBlur(x: Float) {
        if (Build.VERSION.SDK_INT < 31) return
        val r = home.resources.displayMetrics.density * HOME_BLUR_DP * x
        if (r <= 0.5f) { home.setRenderEffect(null); return }
        val blur = RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP)
        val wash = DrawerInk.wash(home.context)
        val alpha = (HOME_WASH_ALPHA * x * 255f).toInt().coerceIn(0, 255)
        val tint = (wash and 0x00FFFFFF) or (alpha shl 24)
        home.setRenderEffect(
            RenderEffect.createColorFilterEffect(PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_ATOP), blur)
        )
    }

private val drawerStiffness = 380f                      // higher = faster
// fast and smooth: stiff spring, light damping on open (a small settle at the top), critical on close
    // tap / button open: fast and smooth, tiny settle
    private val openStiffness = 700f
    private val openDamping = 2f * 0.72f * kotlin.math.sqrt(openStiffness)
    // swipe-up open: softer, so it overshoots the top and bounces back
    private val swipeOpenStiffness = 520f
    private val swipeOpenDamping = 2f * 0.72f * kotlin.math.sqrt(swipeOpenStiffness)
    // closing: no bounce
    private val closeStiffness = 560f
    private val closeDamping = 2f * 1.00f * kotlin.math.sqrt(closeStiffness)
    private var springK = openStiffness
    private var springC = openDamping
    private var springTargetState: State? = null
    private var springTargetProgress = 0f
    private var springVel = 0f
    private var springRunning = false
    private var springLastMs = 0L
    private val springTick = Runnable { stepDrawerSpring() }

    /** Progress at which the sheet's top edge sits [OPEN_FROM_SCREEN] of the way down the screen. */
    private fun openStartProgress(): Float {
        val h = apps.height.toFloat()
        val top = sheetTop()
        val t = (h - top).coerceAtLeast(1f)
        return (1f - (h * OPEN_FROM_SCREEN - top) / t).coerceIn(0f, 1f)
    }

    private fun run(target: State, velocityPxPerSec: Float = 0f, fromDrag: Boolean = false) {
        anim?.cancel()
        springAnim?.cancel()
        springRunning = false
        apps.removeCallbacks(springTick)
        prepare()
        background.suppressDrawerTint = false                   // ADD

        // a tap/button open: the sheet is already visible 90% of the way down and only travels the rest
        if (target == State.APPS && !fromDrag) {
            val start = openStartProgress()
            if (a < start) { a = start; frame(start) }
        }

        springTargetState = target
        state = target                                  // the swipe counts as done as soon as it's released
        springTargetProgress = if (target == State.APPS) 1f else 0f
            when {
            target != State.APPS -> { springK = closeStiffness; springC = closeDamping }
            fromDrag -> { springK = swipeOpenStiffness; springC = swipeOpenDamping }
            else -> { springK = openStiffness; springC = openDamping }
        }
        val dir = if (toApps) 1f else -1f
        springVel = (dir * velocityPxPerSec / travel()).coerceIn(-8f, 8f)
        if (target == State.APPS && !fromDrag) springVel = maxOf(springVel, OPEN_KICK)
        springRunning = true
        springLastMs = android.view.animation.AnimationUtils.currentAnimationTimeMillis()
        apps.postOnAnimation(springTick)
    }

    private fun stepDrawerSpring() {
        if (!springRunning) return
        val targetState = springTargetState ?: run {
            springRunning = false
            return
        }
        val now = android.view.animation.AnimationUtils.currentAnimationTimeMillis()
        var remaining = ((now - springLastMs).coerceIn(1L, 32L)) / 1000f
        springLastMs = now
        
        var nextA = a
        while (remaining > 0f) {                        // small fixed steps: stays smooth and stable after a slow frame
            val h = minOf(remaining, 0.008f)
            springVel += (-springK * (nextA - springTargetProgress) - springC * springVel) * h
            nextA += springVel * h
            remaining -= h
        }

        if (kotlin.math.abs(springTargetProgress - nextA) < 0.004f && kotlin.math.abs(springVel) < 0.05f) {
            springRunning = false
            springTargetState = null
            springVel = 0f
            frame(springTargetProgress)
            settle(targetState, keepHome = targetState == State.APPS)
            return
        }

        frame(nextA.coerceIn(0f, 1.10f))                // past 1 = the overshoot at the top                // past 1 = the overshoot at the top
        apps.postOnAnimation(springTick)
    }
    /** Search: Home and the Apps screen only crossfade (the search page does the moving). */
private fun fade(target: State, duration: Long) {
    stopTint()
        anim?.cancel()
        springAnim?.cancel()
        toApps = target == State.APPS
        prepare()
        background.suppressDrawerTint = target == State.HOME    // ADD
        apps.translationY = 0f
        home.scaleX = 1f; home.scaleY = 1f
        home.alpha = 1f
        homeBlur(ramp(a))
        background.appsDimScale = 1f
        val end = if (toApps) 1f else 0f
        anim = ValueAnimator.ofFloat(a, end).apply {
            this.duration = max(100L, (duration * abs(end - a)).toLong())
            interpolator = Easing.CUBIC_OUT
            addUpdateListener {
                a = it.animatedValue as Float
                apps.alpha = a
                home.alpha = 1f
                homeBlur(ramp(a))
                background.set(BackgroundController.Kind.APPS, a)
            }
            addListener(object : AnimatorListenerAdapter() {
                var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) { if (!cancelled) settle(target) }
            })
            start()
        }
    }

    private fun settle(target: State, keepHome: Boolean = false) {
        stopTint()
        background.suppressDrawerTint = false                   // ADD
        if (target == State.HOME) startCloseTint()
        val showApps = target == State.APPS
        home.setLayerType(View.LAYER_TYPE_NONE, null)
        apps.setLayerType(View.LAYER_TYPE_NONE, null)
        setScreen(apps, showApps)
        if (!showApps) {
            homeBlur(0f)
            setScreen(home, true)
        } else {
            home.visibility = View.VISIBLE
            home.alpha = 1f
            homeBlur(1f)
        }
        a = if (showApps) 1f else 0f
        background.appsDimScale = 1f
        background.set(BackgroundController.Kind.APPS, a)
        f = 0f
        dragging = false
        state = target
        onStateChanged?.invoke(state)
    }

    private fun setScreen(v: View, shown: Boolean) {
        v.scaleX = 1f; v.scaleY = 1f; v.translationY = 0f
        v.alpha = if (shown) 1f else 0f
        v.visibility = if (shown) View.VISIBLE else View.INVISIBLE
    }

    private companion object {
        const val OPEN_MS = 420L
        /** Blur radius of Home's icons and widgets at full blur, in dp. */
        const val HOME_BLUR_DP = 14f
        /** How much of the sheet's colour washes over Home at full blur (0..1). */
        const val HOME_WASH_ALPHA = 0f
        /** Share of the swipe after which the blur is complete. */
        const val BLUR_FULL_AT = 0.6f
        /** How far a swipe has to go (0…1 of its distance, about 185dp) to open or close. */
        const val COMMIT_AT = 0.8f
        /** A swipe shows nothing until it has lasted this long (ms), unless it is already a long one. */
        const val QUICK_MS = 120L
        const val REVEAL_DP = 80f
        /** Where the sheet's top edge starts when opened by a tap: this share of the screen height from the top (0.90 = 90%). */
        const val OPEN_FROM_SCREEN = 0.90f
        /** Initial push (progress per second) on a tap open. */
        const val OPEN_KICK = 3f
                /** How long the tint takes to fade out on a swipe down / close (ms). Lower = faster. */
        const val CLOSE_TINT_MS = 220L
    }
}
