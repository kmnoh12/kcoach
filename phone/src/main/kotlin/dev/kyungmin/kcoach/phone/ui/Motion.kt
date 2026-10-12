package dev.kyungmin.kcoach.phone.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

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
    val Bg = Color(0xFF0B0C0C)
    val Surface = Color(0xFF161A19)
    val SurfaceRaised = Color(0xFF1F2322)
    val Selected = Color(0xFF262B2A)
    val White = Color(0xFFF2F4F3)
    val Muted = Color(0xFF8A9390)
    val Soft = Color(0xFF98A3A0)
    val Faint = Color(0xFF4F5654)
    val Hairline = Color(0xFF232827)
    val Accent = Color(0xFF2DD4BF)
    val OnAccent = Color(0xFF04201C)
    val AccentWash = Color(0xFF10312C)
    val Red = Color(0xFFE5483B)
}

/** Radii: slightly rounded rectangles, never pills. */
object KShape {
    val Control = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
    val Panel = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
    val Sheet = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
}
