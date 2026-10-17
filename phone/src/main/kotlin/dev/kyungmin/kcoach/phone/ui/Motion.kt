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
    val Bg = Color(0xFF151514)
    val Surface = Color(0xFF1D1C1A)
    val SurfaceRaised = Color(0xFF2A2927)
    val Selected = Color(0xFF32312E)
    val White = Color(0xFFF1EEE7)
    val Muted = Color(0xFF94908A)
    val Soft = Color(0xFFA39F98)
    val Faint = Color(0xFF5A5751)
    val Hairline = Color(0xFF262624)
    /** Bone: the lead colour for big numbers and primary buttons. */
    val Accent = Color(0xFFEDE8DC)
    /** Muted sage: state only (measuring, today, progress, records). */
    val Sage = Color(0xFF8FCFBA)
    val OnAccent = Color(0xFF171612)
    val AccentWash = Color(0xFF1F2B27)
    val Red = Color(0xFFE5483B)
}

/** Radii: slightly rounded rectangles, never pills. */
object KShape {
    val Control = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
    val Panel = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
    val Sheet = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
}
