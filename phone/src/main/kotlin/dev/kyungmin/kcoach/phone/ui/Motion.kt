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
    val Bg = Color(0xFF000000)
    val Surface = Color(0xFF0E0E0D)
    val SurfaceRaised = Color(0xFF1C1B19)
    val Selected = Color(0xFF262522)
    /** Bone: body text, and anything already done or in the past (workout days, past records). */
    val Bone = Color(0xFFEDE8DC)
    val White = Bone
    val Muted = Color(0xFF8F8B82)
    val Soft = Color(0xFFA39F98)
    val Faint = Color(0xFF5C5952)
    val Hairline = Color(0xFF1E1D1A)
    /** Sage: the lead colour for actions and state (primary buttons, today, current set, progress). */
    val Accent = Color(0xFF76DBB9)
    val Sage = Accent
    val OnAccent = Color(0xFF04140E)
    val AccentWash = Color(0xFF0F2A22)
    val Red = Color(0xFFE5483B)
    /** Red with a touch of coral: the filled destructive button (end without saving), white text on top. */
    val DangerFill = Color(0xFFF0594B)
}

/** Radii: slightly rounded rectangles, never pills. */
object KShape {
    val Control = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
    val Panel = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
    val Sheet = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
}
