package com.oneuiclone.home.ui

import androidx.compose.animation.core.EaseInOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect

private const val FOOT_FADE_START = 0.6f
private const val FOOT_FADE_STOPS = 8

/** BitChord's 9-stop EaseInOut foot fade so progressive blur dissolves with zero hard edge. */
private val FootFade = Brush.verticalGradient(
    colorStops = Array(FOOT_FADE_STOPS + 1) { i ->
        val t = i / FOOT_FADE_STOPS.toFloat()
        val at = FOOT_FADE_START + (1f - FOOT_FADE_START) * t
        at to Color.Black.copy(alpha = 1f - EaseInOut.transform(t))
    },
)

/**
 * BitChord's [TopFadeBlur] ported for Launcher You's App Drawer & Search top edge:
 * blurs scrolling icons underneath the status bar and floating search pill at 1/3 scale
 * with a progressive vertical ramp from [maxBlur] down to 0.dp.
 */
@OptIn(ExperimentalHazeApi::class)
@Composable
fun LauncherTopFadeBlur(
    hazeState: HazeState,
    topChromeHeight: Dp,
    modifier: Modifier = Modifier,
    fadeRun: Dp = 8.dp,
    maxBlur: Dp = 30.dp,
    tintColor: Color = Color(0x4D808080), // #808080 at 30% opacity
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(topChromeHeight + fadeRun)
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(FootFade, blendMode = BlendMode.DstIn)
            }
            .hazeEffect(state = hazeState) {
                backgroundColor = tintColor
                tints = emptyList()
                blurRadius = maxBlur
                noiseFactor = 0f
                inputScale = HazeInputScale.Fixed(0.33f)
                progressive = HazeProgressive.verticalGradient(
                    easing = EaseInOut,
                    startIntensity = 1f,
                    endIntensity = 0f,
                )
            },
    )
}

/**
 * BitChord's frosted surface modifier (no liquid glass refraction/rim) configured with
 * Launcher You's #808080 10% tint, 100.dp blur, and 0.75.dp subtle hairline border.
 */
@OptIn(ExperimentalHazeApi::class)
fun Modifier.launcherFrostedGlass(
    hazeState: HazeState,
    isPill: Boolean = false,
    cornerRadius: Dp = 24.dp,
    blurRadius: Dp = 100.dp,
    fillColor: Color = Color(0x1A808080),   // #808080 at 10% opacity
    borderColor: Color = Color(0x1AFFFFFF), // Subtle hairline border
): Modifier {
    val shape = if (isPill) CircleShape else RoundedCornerShape(cornerRadius)
    return this
        .clip(shape)
        .hazeEffect(state = hazeState) {
            this.blurRadius = blurRadius
            this.noiseFactor = 0f
            this.inputScale = HazeInputScale.Fixed(0.33f)
        }
        .background(fillColor, shape)
        .border(0.75.dp, borderColor, shape)
}
