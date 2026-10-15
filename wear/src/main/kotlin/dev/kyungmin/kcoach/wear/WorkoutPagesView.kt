package dev.kyungmin.kcoach.wear

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.LinearLayout
import android.widget.ScrollView
import kotlin.math.abs

/** The counter and controls each occupy the watch. Long controls scroll in the same full viewport. */
internal class WorkoutPagesView(context: Context, private val primary: View, private val controls: View) : ScrollView(context) {
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downY = 0f
    private var downScroll = 0
    private var flung = false

    init {
        isFillViewport = true
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(primary, LinearLayout.LayoutParams(-1, -2))
            addView(controls, LinearLayout.LayoutParams(-1, -2))
        }, LayoutParams(-1, -2))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val viewport = (View.MeasureSpec.getSize(heightMeasureSpec) - paddingTop - paddingBottom).coerceAtLeast(0)
        // The counter is exactly one viewport; otherwise font padding can leave its ring
        // overlapping the controls after a page switch.
        primary.layoutParams.height = viewport
        controls.minimumHeight = viewport
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun beginGesture(event: MotionEvent) {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            downY = event.y; downScroll = scrollY; flung = false
        }
    }
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        beginGesture(event)
        return super.onInterceptTouchEvent(event)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        beginGesture(event)
        val handled = super.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP && !flung && abs(event.y - downY) > touchSlop && scrollY in 1 until primary.height) {
            smoothScrollTo(0, if (scrollY > downScroll) primary.height else 0)
        }
        return handled
    }
    override fun fling(velocityY: Int) {
        flung = true
        when {
            velocityY > 0 && scrollY < primary.height -> smoothScrollTo(0, primary.height)
            velocityY < 0 && scrollY <= primary.height -> smoothScrollTo(0, 0)
            else -> super.fling(velocityY)
        }
    }
}
