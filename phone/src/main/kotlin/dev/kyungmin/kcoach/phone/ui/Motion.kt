package dev.kyungmin.kcoach.phone.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.Color

/**
 * One motion language for the whole app: large panels move with a 300ms
 * easeInOutCirc, small state changes with a 200ms easeInOut, and numbers roll
 * linearly. Springs and bounces are deliberately not used.
 */
object Motion {
    val PanelEasing = CubicBezierEasing(0.785f, 0.135f, 0.15f, 0.86f)
    val UiEasing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)
    val PageEasing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)

    const val PANEL_MS = 300
    const val UI_MS = 200
    const val DIGIT_MS = 180
    const val PAGE_MS = 300
    const val RING_INTRO_MS = 900

    fun <T> panel(): TweenSpec<T> = tween(PANEL_MS, easing = PanelEasing)
    fun <T> ui(): TweenSpec<T> = tween(UI_MS, easing = UiEasing)
    fun <T> page(): TweenSpec<T> = tween(PAGE_MS, easing = PageEasing)
    fun <T> digit(): TweenSpec<T> = tween(DIGIT_MS, easing = LinearEasing)
}

object KColors {
    val Bg = Color(0xFF0A0C0C)
    val Surface = Color(0xFF171A1A)
    val SurfaceRaised = Color(0xFF1F2222)
    val Selected = Color(0xFF262727)
    val White = Color(0xFFF5F6F6)
    val Muted = Color(0xFF919696)
    val Faint = Color(0xFF4F5454)
    val Amber = Color(0xFFFFB100)
    val AmberDark = Color(0xFFD08000)
    val Red = Color(0xFFE5483B)
    val Blue = Color(0xFF538EFF)
    val Green = Color(0xFF66E299)
}
