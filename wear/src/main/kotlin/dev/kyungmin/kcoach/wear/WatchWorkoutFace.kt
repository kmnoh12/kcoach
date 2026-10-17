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
    /** Lines placed by their baseline, the way the design sheet's line-height:1 boxes put them (design units). */
    private val baselines = HashMap<TextView, Float>()
    /** Lines that shrink to fit their box instead of being cut (three-digit counts, long notices). */
    private val fitting = HashSet<TextView>()
    /** Text sizes live on the 432-unit design grid (in the old sp numbers) and scale with the screen, not the user's density. */
    private val designSp = HashMap<TextView, Float>()
    fun size(view: TextView, sp: Float) { designSp[view] = sp; applyTextSize(view) }
    private fun applyTextSize(view: TextView) {
        val sp = designSp[view] ?: return
        val scale = minOf(width, height) / 432f
        if (scale <= 0f) { view.textSize = sp; return }
        var px = sp * 2f * scale
        val box = positions[view]?.width()?.times(scale)
        if (view in fitting && box != null && box > 0f) {
            view.paint.textSize = px
            val wanted = android.text.Layout.getDesiredWidth(view.text, view.paint)
            if (wanted > box) px *= box / wanted * .98f
        }
        view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px)
    }
    private val divider = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(38, 38, 36); strokeWidth = 1.5f }
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val clock = label(13f, WHITE, 116, 42, 316, 72)
    val title = label(17f, WHITE, 63, 74, 369, 110, true).apply { maxLines = 1 }
    val subtitle = label(11f, MUTED, 52, 72, 380, 142)
    val jog = JogBar(context).also { positions[it] = android.graphics.Rect(80, 202, 352, 298); addView(it, LayoutParams(1, 1)); it.visibility = GONE }
    val chevronUp = ChevronView(context, true).also { positions[it] = android.graphics.Rect(199, 14, 233, 31); addView(it, LayoutParams(1, 1)); it.visibility = GONE }
    val chevronDown = ChevronView(context, false).also { positions[it] = android.graphics.Rect(199, 398, 233, 415); addView(it, LayoutParams(1, 1)); it.visibility = GONE }
    /** The rest screen's card for the next set: a muted label on the left, the set itself on the right. */
    val restCard = View(context).also {
        positions[it] = android.graphics.Rect(72, 296, 360, 350); addView(it, LayoutParams(1, 1)); it.visibility = GONE
        it.background = android.graphics.drawable.GradientDrawable().apply { setColor(CARD) }
    }
    val restNextLabel = label(9.5f, SOFT, 92, 300, 200, 346).apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL; visibility = GONE }
    val restNextValue = label(12.5f, WHITE, 200, 300, 340, 346).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL; visibility = GONE }
    val valueGhost = label(88f, BONE, 50, 140, 270, 320, false).apply { alpha = 0f; isClickable = false; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    val value = label(88f, BONE, 50, 140, 270, 320, false)
    val target = label(15f, SOFT, 270, 232, 382, 292)
    /** The weight with its small "kg" on one line, as the design sets them. */
    val weight = label(17f, WHITE, 84, 336, 176, 378, false)
    val heart = HeartView(context).also { positions[it] = android.graphics.Rect(232, 340, 266, 374); addView(it, LayoutParams(1, 1)) }
    val heartRate = label(17f, WHITE, 268, 336, 352, 378, false)
    val hint = label(11f, MUTED, 76, 370, 356, 400).apply { isClickable = true }
    val down = label(12f, MUTED, 180, 391, 252, 419)
    val restGo = label(10.5f, SOFT, 96, 366, 336, 394, false).apply { visibility = GONE; isClickable = true; isFocusable = true }
    val action = label(16f, Color.rgb(23, 22, 18), 76, 280, 356, 376, true).apply {
        tag = "phone-result"; isClickable = true; isFocusable = true
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(BONE); cornerRadius = 6f * resources.displayMetrics.density
        }
    }
    init {
        listOf(weight, heartRate, clock, value, valueGhost, restNextValue).forEach { it.typeface = medium }
        // Design baselines: top + 0.841 x size for the sheet's line-height:1 text.
        baselines[clock] = 63.9f; baselines[title] = 100.6f; baselines[subtitle] = 134.5f
        baselines[restNextLabel] = 330.5f; baselines[restNextValue] = 332.5f; baselines[restGo] = 385.7f
        baselines[hint] = 392f
        fitting += listOf(value, valueGhost, hint, restNextValue)
    }
    var showDivider = true
    init { setBackgroundColor(Color.BLACK); setWillNotDraw(false) }
    private fun label(sp: Float, color: Int, left: Int, top: Int, right: Int, bottom: Int, bold: Boolean = false) =
        TextView(context).apply {
            textSize = sp; setTextColor(color); gravity = Gravity.CENTER; includeFontPadding = false
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            addView(this, LayoutParams(1, 1)); positions[this] = android.graphics.Rect(left, top, right, bottom)
            designSp[this] = sp
        }
    private fun place(view: View, l: Int, t: Int, r: Int, b: Int) {
        val rect = android.graphics.Rect(l, t, r, b)
        if (positions[view] != rect) { positions[view] = rect; requestLayout() }
    }
    private fun baseline(view: TextView, line: Float?) {
        if (baselines[view] == line) return
        if (line == null) baselines.remove(view) else baselines[view] = line
        requestLayout()
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val scale = minOf(measuredWidth, measuredHeight) / 432f
        designSp.keys.forEach { applyTextSize(it) }
        // Long exercise names shrink to fit one line, between 11 and 17 design sp.
        androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(title, (22f * scale).toInt().coerceAtLeast(8), (34f * scale).toInt().coerceAtLeast(10), 1, android.util.TypedValue.COMPLEX_UNIT_PX)
        (restCard.background as? android.graphics.drawable.GradientDrawable)?.cornerRadius = 18f * scale
        positions.forEach { (view, rect) ->
            var height = (rect.height() * scale).toInt()
            if (view === action) height = maxOf(height, (48 * resources.displayMetrics.density).toInt())
            // A line of text needs about 1.4 times its size; a tighter box would cut the bottom of Korean glyphs.
            if (view is TextView && view !== title) height = maxOf(height, (view.textSize * 1.4f).toInt())
            if (view === title) height = maxOf(height, (34f * scale * 1.4f).toInt())
            if (view is TextView && view in baselines) height = if (view === title) (34f * scale * 1.6f).toInt() else (view.textSize * 1.6f).toInt()
            view.measure(MeasureSpec.makeMeasureSpec((rect.width() * scale).toInt(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
        }
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val side = minOf(width, height); val scale = side / 432f
        val offsetX = (width - side) / 2; val offsetY = (height - side) / 2
        positions.forEach { (view, rect) ->
            val l = offsetX + (rect.left * scale).toInt(); val r = offsetX + (rect.right * scale).toInt()
            val centreY = offsetY + (rect.centerY() * scale)
            val line = (view as? TextView)?.let { baselines[it] }
            if (view === action) {
                val bottomPx = offsetY + (rect.bottom * scale).toInt()
                view.layout(l, bottomPx - view.measuredHeight, r, bottomPx)
            } else if (view is TextView && line != null) {
                val t = Math.round(offsetY + line * scale - view.baseline)
                view.layout(l, t, r, t + view.measuredHeight)
            } else if (view is TextView) {
                // Keep the text where the design puts it even when the box grew to avoid clipping.
                val half = view.measuredHeight / 2f
                view.layout(l, (centreY - half).toInt(), r, (centreY + half).toInt())
            } else view.layout(l, offsetY + (rect.top * scale).toInt(), r, offsetY + (rect.bottom * scale).toInt())
        }
    }
    private var mode = -1
    /** The counter sits left of its "/ 12회" while measuring and is centred (and wider) for the rest timer and home. */
    fun primary(mode: Int) {
        this.mode = mode
        when (mode) {
            2 -> listOf(value, valueGhost).forEach { place(it, 44, 96, 388, 224); baseline(it, 206f); it.letterSpacing = -4f / 126f }
            1 -> listOf(value, valueGhost).forEach { place(it, 44, 120, 388, 300); baseline(it, null); it.letterSpacing = 0f }
            else -> listOf(value, valueGhost).forEach { it.letterSpacing = -4f / 176f }
        }
        baseline(subtitle, if (mode == 2) 96.5f else 134.5f)
    }

    /**
     * The design's measuring row: a 204-wide number window, a 14 gap and "/ 12회", centred together. A word in
     * place of the count ("준비") sits higher, in the middle of the window.
     */
    fun layoutCount() {
        if (mode != 0) return
        val ofWidth = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 30f }.measureText(target.text.toString())
        val left = (216f - (204f + 14f + ofWidth) / 2f).toInt()
        val numeric = value.text.firstOrNull()?.isDigit() == true || value.text.toString() == "—"
        listOf(value, valueGhost).forEach { place(it, left, 140, left + 204, 320); baseline(it, if (numeric) 290f else 262f) }
        place(target, left + 218 - 6, 250, left + 218 + ofWidth.toInt() + 6, 290); baseline(target, 278.8f)
    }

    /** The weight with its unit and the heart with its rate, centred as the design's two groups 44 units apart. */
    fun layoutMetrics() {
        val big = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = medium; textSize = 34f }
        val weightWidth = android.text.Layout.getDesiredWidth(weight.text, big)
        val rateWidth = big.measureText(heartRate.text.toString())
        val showHeart = heart.visibility == VISIBLE
        val total = weightWidth + 44f + (if (showHeart) 32.4f + 6f else 0f) + rateWidth
        var x = 216f - total / 2f
        place(weight, (x - 4).toInt(), 336, (x + weightWidth + 4).toInt(), 378); baseline(weight, 366.6f)
        x += weightWidth + 44f
        if (showHeart) { place(heart, x.toInt(), 339, (x + 32.4f).toInt(), 371); x += 32.4f + 6f }
        place(heartRate, (x - 4).toInt(), 336, (x + rateWidth + 4).toInt(), 378); baseline(heartRate, 366.6f)
    }

    /** The rest card: "다음 2세트" on the left, "12회 × 20kg" on the right; the set shrinks when both do not fit. */
    fun bindRestCard(label: String, set: CharSequence) {
        restNextLabel.text = label; restNextValue.text = set
        val labelWidth = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 19f }.measureText(label)
        place(restNextLabel, 92, 300, (92 + labelWidth + 6).toInt(), 346)
        place(restNextValue, (92 + labelWidth + 14).toInt(), 300, 340, 346)
    }

    /** The status line under the exercise name; a small coloured dot in front of it marks a live measurement. */
    fun setSubtitle(text: String, color: Int, dot: Int? = null, sizeSp: Float = 11f, bold: Boolean = false, medium: Boolean = false) {
        subtitle.setTextColor(color); size(subtitle, sizeSp)
        subtitle.typeface = when { bold -> Typeface.DEFAULT_BOLD; medium -> this.medium; else -> Typeface.DEFAULT }
        subtitle.setCompoundDrawablesRelative(null, null, null, null)
        // The rest screen's "+10초" flash is the larger 30-unit line in the same place.
        if (mode == 2) baseline(subtitle, if (sizeSp >= 15f) 103.2f else 96.5f)
        if (dot == null) subtitle.text = text
        else {
            val scale = minOf(width, height).coerceAtLeast(1) / 432f
            // A zero-width placeholder carries the dot so it stays next to the text however long the text is.
            subtitle.text = android.text.SpannableString("​$text").apply {
                setSpan(DotSpan(dot, (9f * scale).toInt().coerceAtLeast(6), (10f * scale).toInt().coerceAtLeast(6)), 0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    /** A tappable notice (retained counts, an update waiting for approval) shown in the hint line. */
    var noticeAction: (() -> Unit)? = null
    fun homeLayout(home: Boolean) {
        // At home the notice is two lines in the middle; otherwise one line that ends above the bottom arrow.
        val rect = if (home) android.graphics.Rect(60, 290, 372, 377) else android.graphics.Rect(76, 370, 356, 400)
        if (positions[hint] != rect) { positions[hint] = rect; hint.maxLines = if (home) 2 else 1; requestLayout() }
        baseline(hint, if (home) null else 392f)
        if (home) fitting -= hint else fitting += hint
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val side = minOf(width, height); val scale = side / 432f
        val x = (width - side) / 2f; val y = (height - side) / 2f
        if (showDivider) {
            divider.strokeWidth = 1.5f * scale
            canvas.drawLine(x + 96 * scale, y + 322.75f * scale, x + 336 * scale, y + 322.75f * scale, divider)
            canvas.drawLine(x + 216 * scale, y + 336 * scale, x + 216 * scale, y + 374 * scale, divider)
        }
    }
    companion object {
        val BONE = Color.rgb(237, 232, 220)
        val SAGE = Color.rgb(143, 207, 186)
        /** Rest: a soft yellow. */
        val YELLOW = Color.rgb(255, 222, 112)
        /** Over the target: a light amber, kept distinct from the rest yellow. */
        val ORANGE = Color.rgb(255, 180, 94)
        /** The second lap of the over-target ring. */
        val DEEP_AMBER = Color.rgb(224, 135, 46)
        /** Heart-rate zone 4 stays the strong orange. */
        private val ZONE_ORANGE = Color.rgb(255, 160, 51)
        val RED = Color.rgb(229, 72, 59)
        val WHITE = Color.rgb(241, 238, 231)
        val MUTED = Color.rgb(148, 144, 138)
        val SOFT = Color.rgb(163, 159, 152)
        val CARD = Color.rgb(42, 41, 39)
        val TRACK = Color.rgb(27, 27, 26)
        /** Heart-rate zone colours, zone 1..5. The bands are a placeholder until a max heart rate is known. */
        private val ZONES = intArrayOf(Color.rgb(124, 142, 156), Color.rgb(77, 163, 255), Color.rgb(79, 209, 139), ZONE_ORANGE, RED)
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

/** A small filled circle drawn in front of a line of text, vertically centred on the text. */
private class DotSpan(private val color: Int, private val diameter: Int, private val gap: Int) : android.text.style.ReplacementSpan() {
    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int = diameter + gap
    override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        val dot = Paint(paint).apply { this.color = this@DotSpan.color; style = Paint.Style.FILL }
        canvas.drawCircle(x + diameter / 2f, y + (paint.ascent() + paint.descent()) / 2f, diameter / 2f, dot)
    }
}
