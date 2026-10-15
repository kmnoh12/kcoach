package dev.kyungmin.kcoach.wear

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView

/** D2's hierarchy in real accessible Android views; coordinates follow the approved 216dp round face. */
internal class WatchWorkoutFace(context: Context) : FrameLayout(context) {
    private val positions = mutableMapOf<View, android.graphics.Rect>()
    private val divider = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(48, 56, 63); strokeWidth = 1f }
    val clock = label(12f, WHITE, 116, 16, 316, 50)
    val title = label(16f, WHITE, 63, 60, 369, 103, true)
    val subtitle = label(12f, MUTED, 52, 108, 380, 143)
    val value = label(48f, GREEN, 68, 158, 282, 265, true)
    val target = label(14f, MUTED, 244, 211, 340, 258)
    val weight = label(22f, WHITE, 86, 296, 174, 347, true)
    val unit = label(12f, MUTED, 170, 306, 204, 348)
    val heart = label(16f, RED, 231, 299, 267, 346)
    val heartRate = label(22f, RED, 268, 296, 344, 347, true)
    val hint = label(12f, MUTED, 60, 352, 372, 391)
    val down = label(12f, MUTED, 180, 391, 252, 419)
    val action = label(16f, Color.rgb(6, 26, 19), 76, 280, 356, 376, true).apply {
        tag = "phone-result"; isClickable = true; isFocusable = true
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(GREEN); cornerRadius = 6f * resources.displayMetrics.density
        }
    }
    var showDivider = true
    init { setBackgroundColor(Color.BLACK); setWillNotDraw(false) }
    private fun label(sp: Float, color: Int, left: Int, top: Int, right: Int, bottom: Int, bold: Boolean = false) =
        TextView(context).apply {
            textSize = sp; setTextColor(color); gravity = Gravity.CENTER; includeFontPadding = false
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            addView(this, LayoutParams(1, 1)); positions[this] = android.graphics.Rect(left, top, right, bottom)
        }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val scale = minOf(measuredWidth, measuredHeight) / 432f
        positions.forEach { (view, rect) ->
            val height = (rect.height() * scale).toInt().let {
                if (view === action) maxOf(it, (48 * resources.displayMetrics.density).toInt()) else it
            }
            view.measure(MeasureSpec.makeMeasureSpec((rect.width() * scale).toInt(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
        }
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val side = minOf(width, height); val scale = side / 432f
        val offsetX = (width - side) / 2; val offsetY = (height - side) / 2
        positions.forEach { (view, rect) ->
            val bottomPx = offsetY + (rect.bottom * scale).toInt()
            val topPx = if (view === action) bottomPx - view.measuredHeight else offsetY + (rect.top * scale).toInt()
            view.layout(offsetX + (rect.left * scale).toInt(), topPx, offsetX + (rect.right * scale).toInt(), bottomPx)
        }
    }
    fun primary(centered: Boolean) {
        val rect = if (centered) android.graphics.Rect(55, 158, 377, 265) else android.graphics.Rect(68, 158, 282, 265)
        if (positions[value] != rect) { positions[value] = rect; requestLayout() }
    }
    fun homeLayout(home: Boolean) {
        val rect = if (home) android.graphics.Rect(60, 290, 372, 377) else android.graphics.Rect(60, 352, 372, 391)
        if (positions[hint] != rect) { positions[hint] = rect; hint.maxLines = if (home) 2 else 1; requestLayout() }
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (showDivider) {
            val side = minOf(width, height); val scale = side / 432f
            val x = (width - side) / 2f; val y = (height - side) / 2f
            canvas.drawLine(x + 93 * scale, y + 279 * scale, x + 339 * scale, y + 279 * scale, divider)
        }
    }
    companion object {
        val GREEN = Color.rgb(0, 230, 140)
        val ORANGE = Color.rgb(255, 152, 0)
        val RED = Color.rgb(255, 105, 105)
        val WHITE = Color.rgb(245, 247, 250)
        val MUTED = Color.rgb(164, 177, 190)
    }
}
