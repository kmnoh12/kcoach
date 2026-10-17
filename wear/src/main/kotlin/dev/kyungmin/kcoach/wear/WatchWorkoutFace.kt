package dev.kyungmin.kcoach.wear

import android.animation.Keyframe
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView

/** The workout face in real accessible Android views; coordinates follow the 432-unit round design grid. */
internal class WatchWorkoutFace(context: Context) : FrameLayout(context) {
    private val positions = mutableMapOf<View, android.graphics.Rect>()
    private val divider = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(38, 38, 36); strokeWidth = 1.5f }
    private val chevron = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = SOFT; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    val clock = label(13f, WHITE, 116, 42, 316, 72)
    val title = label(17f, WHITE, 63, 74, 369, 110, true)
    val subtitle = label(11f, MUTED, 52, 114, 380, 142)
    val valueGhost = label(88f, BONE, 50, 140, 270, 320, false).apply { alpha = 0f; isClickable = false; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    val value = label(88f, BONE, 50, 140, 270, 320, false)
    val target = label(15f, MUTED, 270, 232, 382, 292)
    val weight = label(17f, WHITE, 84, 336, 176, 378, true)
    val unit = label(10f, MUTED, 178, 346, 208, 378)
    val heart = HeartView(context).also { positions[it] = android.graphics.Rect(232, 340, 266, 374); addView(it, LayoutParams(1, 1)) }
    val heartRate = label(17f, WHITE, 268, 336, 352, 378, true)
    val hint = label(11f, MUTED, 60, 384, 372, 410)
    val down = label(12f, MUTED, 180, 391, 252, 419)
    val restCard = label(12.5f, WHITE, 76, 296, 356, 350, true).apply {
        background = android.graphics.drawable.GradientDrawable().apply { setColor(CARD); cornerRadius = 10f * resources.displayMetrics.density }
        visibility = GONE
    }
    val restGo = label(10.5f, SOFT, 96, 360, 336, 392, true).apply { visibility = GONE; isClickable = true; isFocusable = true }
    val action = label(16f, Color.rgb(23, 22, 18), 76, 280, 356, 376, true).apply {
        tag = "phone-result"; isClickable = true; isFocusable = true
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(BONE); cornerRadius = 6f * resources.displayMetrics.density
        }
    }
    init { listOf(value, valueGhost).forEach { it.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) } }
    var showDivider = true
    var showDownChevron = false
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
    /** The counter sits left of its "/ 12회" while measuring and is centred (and wider) for the rest timer and home. */
    fun primary(centered: Boolean) {
        val rect = if (centered) android.graphics.Rect(44, 120, 388, 300) else android.graphics.Rect(50, 140, 270, 320)
        if (positions[value] != rect) { positions[value] = rect; positions[valueGhost] = rect; requestLayout() }
    }
    fun homeLayout(home: Boolean) {
        val rect = if (home) android.graphics.Rect(60, 290, 372, 377) else android.graphics.Rect(60, 384, 372, 410)
        if (positions[hint] != rect) { positions[hint] = rect; hint.maxLines = if (home) 2 else 1; requestLayout() }
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val side = minOf(width, height); val scale = side / 432f
        val x = (width - side) / 2f; val y = (height - side) / 2f
        if (showDivider) {
            canvas.drawLine(x + 96 * scale, y + 324 * scale, x + 336 * scale, y + 324 * scale, divider)
            canvas.drawLine(x + 216 * scale, y + 338 * scale, x + 216 * scale, y + 374 * scale, divider)
        }
        if (showDownChevron) {
            chevron.strokeWidth = 2.4f * scale
            val cx = x + 216 * scale; val cy = y + 404 * scale
            val path = Path().apply { moveTo(cx - 17 * scale, cy - 4 * scale); lineTo(cx, cy + 6 * scale); lineTo(cx + 17 * scale, cy - 4 * scale) }
            canvas.drawPath(path, chevron)
        }
    }
    companion object {
        val BONE = Color.rgb(237, 232, 220)
        val SAGE = Color.rgb(143, 207, 186)
        val YELLOW = Color.rgb(255, 210, 51)
        val ORANGE = Color.rgb(255, 160, 51)
        val DEEP_AMBER = Color.rgb(196, 88, 0)
        val RED = Color.rgb(229, 72, 59)
        val WHITE = Color.rgb(241, 238, 231)
        val MUTED = Color.rgb(148, 144, 138)
        val SOFT = Color.rgb(163, 159, 152)
        val CARD = Color.rgb(42, 41, 39)
        val TRACK = Color.rgb(27, 27, 26)
        /** Heart-rate zone colours, zone 1..5. The bands are a placeholder until a max heart rate is known. */
        private val ZONES = intArrayOf(Color.rgb(124, 142, 156), Color.rgb(77, 163, 255), Color.rgb(79, 209, 139), ORANGE, RED)
        private const val ASSUMED_MAX_HEART_RATE = 185
        fun zone(heartRate: Int): Int = when {
            heartRate <= 0 -> 0
            heartRate * 100 < ASSUMED_MAX_HEART_RATE * 50 -> 1
            heartRate * 100 < ASSUMED_MAX_HEART_RATE * 60 -> 1
            heartRate * 100 < ASSUMED_MAX_HEART_RATE * 70 -> 2
            heartRate * 100 < ASSUMED_MAX_HEART_RATE * 80 -> 3
            heartRate * 100 < ASSUMED_MAX_HEART_RATE * 90 -> 4
            else -> 5
        }
        fun zoneColor(heartRate: Int): Int = ZONES[(zone(heartRate).coerceAtLeast(1)) - 1]
    }
}

/** A filled heart that beats once per heartbeat; only the heart itself is redrawn, never the whole face. */
internal class HeartView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL_AND_STROKE; strokeJoin = Paint.Join.ROUND }
    private val path = Path()
    private var animator: ObjectAnimator? = null
    private var periodMs = 0L

    init { setLayerType(LAYER_TYPE_HARDWARE, null); visibility = INVISIBLE }

    /** Shows [bpm] in its zone colour and beats at that rate; 0 hides the heart and stops the beat. */
    fun show(bpm: Int) {
        if (bpm <= 0) { stop(); visibility = INVISIBLE; return }
        paint.color = WatchWorkoutFace.zoneColor(bpm)
        invalidate()
        visibility = VISIBLE
        val period = (60_000L / bpm).coerceIn(300L, 1_500L)
        if (animator?.isRunning == true && kotlin.math.abs(period - periodMs) < 40) return
        periodMs = period
        animator?.cancel()
        pivotX = width / 2f; pivotY = height * 0.55f
        val beat = arrayOf(Keyframe.ofFloat(0f, 1f), Keyframe.ofFloat(.14f, 1.22f), Keyframe.ofFloat(.28f, 1f),
            Keyframe.ofFloat(.42f, 1.12f), Keyframe.ofFloat(.56f, 1f), Keyframe.ofFloat(1f, 1f))
        animator = ObjectAnimator.ofPropertyValuesHolder(this,
            PropertyValuesHolder.ofKeyframe(SCALE_X, *beat), PropertyValuesHolder.ofKeyframe(SCALE_Y, *beat.map { Keyframe.ofFloat(it.fraction, it.value as Float) }.toTypedArray())).apply {
            duration = period; repeatCount = ObjectAnimator.INFINITE; interpolator = android.view.animation.LinearInterpolator(); start()
        }
    }

    private fun stop() { animator?.cancel(); animator = null; periodMs = 0L; scaleX = 1f; scaleY = 1f }
    override fun onDetachedFromWindow() { stop(); super.onDetachedFromWindow() }
    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        // No beating while the screen is off or the app is in the background.
        if (!isVisible) animator?.pause() else animator?.takeIf { it.isPaused }?.resume()
    }
    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        paint.strokeWidth = w * 0.05f
        path.reset()
        // Material "favorite" outline on a 24 unit grid.
        val s = minOf(w, h) / 24f
        val ox = (w - 24f * s) / 2f; val oy = (h - 24f * s) / 2f
        fun px(v: Float) = ox + v * s
        fun py(v: Float) = oy + v * s
        path.moveTo(px(12f), py(21.35f))
        path.lineTo(px(10.55f), py(20.03f))
        path.cubicTo(px(5.4f), py(15.36f), px(2f), py(12.28f), px(2f), py(8.5f))
        path.cubicTo(px(2f), py(5.42f), px(4.42f), py(3f), px(7.5f), py(3f))
        path.cubicTo(px(9.24f), py(3f), px(10.91f), py(3.81f), px(12f), py(5.09f))
        path.cubicTo(px(13.09f), py(3.81f), px(14.76f), py(3f), px(16.5f), py(3f))
        path.cubicTo(px(19.58f), py(3f), px(22f), py(5.42f), px(22f), py(8.5f))
        path.cubicTo(px(22f), py(12.28f), px(18.6f), py(15.36f), px(13.45f), py(20.04f))
        path.close()
        canvas.drawPath(path, paint)
    }
}
