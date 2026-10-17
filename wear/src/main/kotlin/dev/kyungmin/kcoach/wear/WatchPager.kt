package dev.kyungmin.kcoach.wear

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Three full-screen pages stacked vertically: [top] above the [main] page and [bottom] below it. Dragging down
 * reveals the top page, dragging up the bottom one.
 */
internal class WatchPager(context: Context, private val main: View, private val top: View, private val bottom: View) : FrameLayout(context) {
    var topEnabled = false
        set(value) { field = value; applyPosition() }
    var bottomEnabled = false
        set(value) { field = value; applyPosition() }
    var onPage: (Int) -> Unit = {}

    /** 0 is the main page, -1 the top page, 1 the bottom page. */
    var page = 0
        private set
    /** Visual position in page units: page p is on screen when this equals p. */
    private var position = 0f
    private var dragging = false
    private var downY = 0f
    private var downX = 0f
    private var startPosition = 0f
    private var animator: ValueAnimator? = null
    private var velocity: VelocityTracker? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val flingVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity * 6f
    private val ease = PathInterpolator(.4f, 0f, .2f, 1f)

    init {
        setBackgroundColor(Color.BLACK)
        addView(top, LayoutParams(-1, -1)); addView(bottom, LayoutParams(-1, -1)); addView(main, LayoutParams(-1, -1))
        applyPosition()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { super.onSizeChanged(w, h, oldw, oldh); applyPosition() }

    /** Moves to [target] (collapsing to the main page when that page is disabled). */
    fun show(target: Int, animate: Boolean = false) {
        val allowed = when (target) { -1 -> topEnabled; 1 -> bottomEnabled; else -> true }
        moveTo(if (allowed) target else 0, animate)
    }

    private fun applyPosition() {
        val h = height.toFloat()
        main.translationY = (0 - position) * h
        top.translationY = (-1 - position) * h
        bottom.translationY = (1 - position) * h
        top.visibility = if (position < -0.001f || page == -1) VISIBLE else INVISIBLE
        bottom.visibility = if (position > 0.001f || page == 1) VISIBLE else INVISIBLE
    }

    private fun moveTo(target: Int, animate: Boolean) {
        animator?.cancel()
        val changed = target != page
        page = target
        if (!animate || height == 0) { position = target.toFloat(); applyPosition(); if (changed) onPage(page); return }
        animator = ValueAnimator.ofFloat(position, target.toFloat()).apply {
            duration = 220; interpolator = ease
            addUpdateListener { position = it.animatedValue as Float; applyPosition() }
            start()
        }
        if (changed) onPage(page)
    }

    private fun canDrag(dy: Float): Boolean = when (page) {
        0 -> if (dy > 0) topEnabled else bottomEnabled
        -1 -> dy < 0
        else -> dy > 0
    }

    /** Claims the gesture once it is a mostly vertical drag in a direction this page can move. */
    private fun startDragIfVertical(e: MotionEvent): Boolean {
        val dy = e.y - downY; val dx = e.x - downX
        if (dragging) return true
        if (abs(dy) > slop && abs(dy) > abs(dx) * 1.2f && canDrag(dy)) {
            dragging = true; animator?.cancel(); parent?.requestDisallowInterceptTouchEvent(true)
            startPosition = position
            return true
        }
        return false
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downY = e.y; downX = e.x; dragging = false; velocity?.recycle(); velocity = VelocityTracker.obtain().also { it.addMovement(e) } }
            MotionEvent.ACTION_MOVE -> { velocity?.addMovement(e); if (startDragIfVertical(e)) return true }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { velocity?.recycle(); velocity = null }
        }
        return dragging
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        velocity?.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downY = e.y; downX = e.x; dragging = false; velocity?.recycle(); velocity = VelocityTracker.obtain().also { it.addMovement(e) }; return true }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && !startDragIfVertical(e)) return true
                val dy = e.y - downY
                val h = height.toFloat().coerceAtLeast(1f)
                position = rubber(startPosition - dy / h)
                applyPosition()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!dragging) { velocity?.recycle(); velocity = null; return true }
                dragging = false
                velocity?.computeCurrentVelocity(1000); val vy = velocity?.yVelocity ?: 0f
                velocity?.recycle(); velocity = null
                val moved = position - page          // > 0: toward the bottom page, < 0: toward the top page
                val towardBottom = moved > .28f || vy < -flingVelocity
                val towardTop = moved < -.28f || vy > flingVelocity
                val target = when {
                    page == 0 && towardBottom && bottomEnabled -> 1
                    page == 0 && towardTop && topEnabled -> -1
                    page == -1 && towardBottom -> 0
                    page == 1 && towardTop -> 0
                    else -> page
                }
                moveTo(target, animate = true)
            }
        }
        return true
    }

    /** The pages follow the finger inside their range and resist beyond the first and last enabled page. */
    private fun rubber(p: Float): Float {
        val lo = if (topEnabled || page == -1) -1f else 0f
        val hi = if (bottomEnabled || page == 1) 1f else 0f
        return when {
            p < lo -> lo + (p - lo) * .25f
            p > hi -> hi + (p - hi) * .25f
            else -> p
        }.coerceIn(-1.2f, 1.2f)
    }
}
