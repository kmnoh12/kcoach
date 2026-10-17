package dev.kyungmin.kcoach.wear

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.Typeface
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.ReplacementSpan
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.widget.TextView
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Shared colours, motion and formatting for the short moment screens (set done, records, calibration, result). */
internal object Moment {
    /** Unlit rim and unlit segments. */
    val TRACK = WatchWorkoutFace.TRACK
    /** The calibration orb's unlit circle. */
    val ORB_TRACK = Color.rgb(31, 31, 29)
    /** The previous-best bar, deliberately dim next to today's sage bar. */
    val PREVIOUS_BAR = Color.rgb(64, 62, 58)
    /** Unlit calibration segments. */
    val SEGMENT_OFF = WatchWorkoutFace.CARD
    /** Hairline between the result columns. */
    val DIVIDER = Color.rgb(46, 45, 42)
    /** Dark text on the bone button. */
    val ON_BONE = Color.rgb(23, 22, 18)
    /** Every slide in the design uses this curve: quick out, no bounce. */
    val EASE = PathInterpolator(.4f, 0f, .2f, 1f)
    /** How long a set-done or record screen stays before the rest screen takes over. */
    const val SET_DONE_MS = 3_500L
    /** One step of a rolling number (the design's 4 percent of a 5.5 second loop). */
    const val ROLL_MS = 220L
    /** The placeholder character that carries a gap or an icon inside a line (a thin space reads naturally aloud). */
    const val GAP = "\u2009"

    /** Accessibility "remove animations": every moment appears in its finished state. */
    fun reducedMotion(context: Context): Boolean =
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

    /** 60 -> "60", 62.5 -> "62.5", 61.25 -> "61.25"; plates never need more than two decimals. */
    fun kg(value: Double): String = java.math.BigDecimal.valueOf(value).setScale(2, java.math.RoundingMode.HALF_UP)
        .stripTrailingZeros().toPlainString()

    /** Draws the rim ring of the design (radius 209, stroke 7 on the 432 grid), clockwise from 12 o'clock. */
    fun drawRim(canvas: Canvas, view: View, paint: Paint, track: Int?, color: Int, fraction: Float) {
        val side = minOf(view.width, view.height).toFloat(); val k = side / 432f
        val cx = view.width / 2f; val cy = view.height / 2f; val r = 209f * k
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 7f * k; paint.strokeCap = Paint.Cap.ROUND
        if (track != null) { paint.color = track; canvas.drawCircle(cx, cy, r, paint) }
        paint.color = color
        when {
            fraction >= 0.999f -> canvas.drawCircle(cx, cy, r, paint)
            fraction > 0.001f -> canvas.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), -90f, 360f * fraction, false, paint)
        }
    }

    /** A large number with a small unit after it, e.g. 62.5 kg or 11 회, as the design sets them. */
    fun valueWithUnit(value: String, unit: String, unitScale: Float, unitColor: Int, gapEm: Float = 0f, regularUnit: Boolean = true): CharSequence =
        SpannableStringBuilder(value).apply {
            if (unit.isEmpty()) return@apply
            if (gapEm > 0f) { val s = length; append(GAP); setSpan(GapSpan(gapEm), s, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
            val start = length
            append(unit)
            setSpan(RelativeSizeSpan(unitScale), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(unitColor), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (regularUnit) setSpan(TypefaceSpanCompat(Typeface.DEFAULT), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

    /** An icon in front of a line of text, vertically centred on the line like the design's flex rows. */
    fun withIcon(icon: IconSpan.Kind, text: String, sizeEm: Float, gapEm: Float, color: Int): CharSequence =
        SpannableStringBuilder(GAP).apply {
            setSpan(IconSpan(icon, sizeEm, gapEm, color), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            append(text)
        }
}

/** Fixed horizontal room inside a line, measured in ems of the surrounding text. */
internal class GapSpan(private val em: Float) : ReplacementSpan() {
    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        if (fm != null) paint.getFontMetricsInt(fm)
        return (em * paint.textSize).roundToInt()
    }
    override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) = Unit
}

/** A typeface for part of a line (TypefaceSpan(Typeface) needs API 28, which every watch has; this keeps it explicit). */
internal class TypefaceSpanCompat(private val face: Typeface) : android.text.style.MetricAffectingSpan() {
    override fun updateDrawState(tp: android.text.TextPaint) { tp.typeface = face }
    override fun updateMeasureState(tp: android.text.TextPaint) { tp.typeface = face }
}

/** The design's line icons (24 unit glyphs, 2.2 unit strokes), centred on the text line. */
internal class IconSpan(private val kind: Kind, private val sizeEm: Float, private val gapEm: Float, private val color: Int) : ReplacementSpan() {
    enum class Kind { UP, CHECK_CIRCLE, CAMERA }
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        if (fm != null) paint.getFontMetricsInt(fm)
        return ((sizeEm + gapEm) * paint.textSize).roundToInt()
    }
    override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        val size = sizeEm * paint.textSize
        // A line-height:1 row centres its items on the line box, 0.341 em above the baseline.
        val cy = y - 0.341f * paint.textSize
        val u = size / 24f
        val ox = x; val oy = cy - size / 2f
        ink.color = color; ink.strokeWidth = 2.2f * u
        val path = Path()
        when (kind) {
            Kind.UP -> { path.moveTo(ox + 12 * u, oy + 19 * u); path.lineTo(ox + 12 * u, oy + 6 * u)
                path.moveTo(ox + 6 * u, oy + 11.5f * u); path.lineTo(ox + 12 * u, oy + 5.5f * u); path.lineTo(ox + 18 * u, oy + 11.5f * u) }
            Kind.CHECK_CIRCLE -> { canvas.drawCircle(ox + 12 * u, oy + 12 * u, 10 * u, ink)
                path.moveTo(ox + 7.5f * u, oy + 12.5f * u); path.lineTo(ox + 10.5f * u, oy + 15.5f * u); path.lineTo(ox + 16.5f * u, oy + 8.5f * u) }
            Kind.CAMERA -> { canvas.drawRoundRect(RectF(ox + 3 * u, oy + 7 * u, ox + 17 * u, oy + 17 * u), 2 * u, 2 * u, ink)
                path.moveTo(ox + 17 * u, oy + 11 * u); path.lineTo(ox + 21 * u, oy + 8.5f * u); path.lineTo(ox + 21 * u, oy + 15.5f * u); path.lineTo(ox + 17 * u, oy + 13 * u) }
        }
        canvas.drawPath(path, ink)
    }
}

/**
 * The completion mark: a circle draws itself from 3 o'clock, the check follows, and the whole mark grows by
 * 7 percent and settles. Timings follow the design's 3.4 s keyframes (circle 0-22 %, check 18-34 %, pop 30-46 %).
 */
internal class DrawnCheckView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = WatchWorkoutFace.SAGE
    }
    private val check = Path(); private val piece = Path(); private val measure = PathMeasure()
    private val drawCurve = PathInterpolator(.5f, 0f, .2f, 1f)
    private val popCurve = PathInterpolator(.42f, 0f, .58f, 1f)
    private var animator: ValueAnimator? = null
    /** Milliseconds into the drawing; [END_MS] is the finished mark. */
    var elapsedMs = END_MS
        private set

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun play() {
        animator?.cancel()
        if (Moment.reducedMotion(context)) { seek(END_MS); return }
        seek(0f)
        animator = ValueAnimator.ofFloat(0f, END_MS).apply {
            duration = END_MS.toLong(); interpolator = LinearInterpolator()
            addUpdateListener { seek(it.animatedValue as Float) }
            start()
        }
    }

    fun seek(ms: Float) { elapsedMs = ms.coerceIn(0f, END_MS); invalidate() }
    fun finish() { animator?.cancel(); animator = null; seek(END_MS) }
    override fun onDetachedFromWindow() { finish(); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        val k = minOf(width, height) / 104f
        val t = elapsedMs
        val circle = drawCurve.getInterpolation((t / 748f).coerceIn(0f, 1f))
        val tick = drawCurve.getInterpolation(((t - 612f) / 544f).coerceIn(0f, 1f))
        val pop = when {
            t < 1020f -> 1f
            t < 1292f -> 1f + .07f * popCurve.getInterpolation((t - 1020f) / 272f)
            t < 1564f -> 1.07f - .07f * popCurve.getInterpolation((t - 1292f) / 272f)
            else -> 1f
        }
        canvas.save()
        canvas.scale(pop, pop, width / 2f, height / 2f)
        paint.strokeWidth = 5.5f * k
        val ox = (width - 104f * k) / 2f; val oy = (height - 104f * k) / 2f
        if (circle > 0f) canvas.drawArc(RectF(ox + 6 * k, oy + 6 * k, ox + 98 * k, oy + 98 * k), 0f, 360f * circle, false, paint)
        if (tick > 0f) {
            check.reset(); check.moveTo(ox + 31 * k, oy + 54.5f * k); check.lineTo(ox + 45.5f * k, oy + 69 * k); check.lineTo(ox + 74 * k, oy + 38 * k)
            measure.setPath(check, false); piece.reset()
            measure.getSegment(0f, measure.length * tick, piece, true)
            canvas.drawPath(piece, paint)
        }
        canvas.restore()
    }

    companion object { const val END_MS = 1564f }
}

/**
 * A number in a clipped window that rolls like the workout counter: the old value slides up and out while the
 * new one rises from below. Geometry is in design units of this window: [windowUnits] tall, baseline at [baselineUnits].
 */
internal class RollingNumberView(context: Context, private val windowUnits: Float, private val baselineUnits: Float,
    private val sizeUnits: Float, private val spacingUnits: Float, color: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private var current = ""
    private var previous = ""
    private var progress = 1f
    private var animator: ValueAnimator? = null
    val text: String get() = current

    fun setNumber(value: String, animate: Boolean) {
        if (value == current) return
        animator?.cancel()
        previous = current; current = value; contentDescription = value
        if (!animate || previous.isEmpty() || Moment.reducedMotion(context)) { progress = 1f; invalidate(); return }
        progress = 0f
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = Moment.ROLL_MS; interpolator = Moment.EASE
            addUpdateListener { progress = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    fun setColor(color: Int) { if (paint.color != color) { paint.color = color; invalidate() } }
    override fun onDetachedFromWindow() { animator?.cancel(); progress = 1f; super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        val k = height / windowUnits
        paint.textSize = sizeUnits * k
        paint.letterSpacing = spacingUnits / sizeUnits
        // A number wider than the window (three digits) shrinks instead of spilling out of it.
        val widest = maxOf(paint.measureText(current), if (progress < 1f) paint.measureText(previous) else 0f)
        if (widest > width * .98f) paint.textSize *= width * .98f / widest
        val baseline = baselineUnits * k
        canvas.save(); canvas.clipRect(0, 0, width, height)
        if (progress < 1f) {
            canvas.drawText(previous, width / 2f, baseline - progress * height, paint)
            canvas.drawText(current, width / 2f, baseline + (1f - progress) * height, paint)
        } else canvas.drawText(current, width / 2f, baseline, paint)
        canvas.restore()
    }
}

/** The record or completion facts the watch can show for a finished set. */
internal data class SetRecord(val kind: String, val weightKg: Double?, val previousWeightKg: Double?, val reps: Int?, val previousReps: Int?) {
    /** Screen 7 needs both loads, today's heavier. */
    val showsWeightBars get() = kind == "HEAVIEST_WEIGHT" && weightKg != null && previousWeightKg != null && weightKg > previousWeightKg && previousWeightKg > 0.0
    /** Screen 8 needs both counts, today's higher. */
    val showsRepDots get() = kind == "SAME_WEIGHT_REPS" && reps != null && previousReps != null && reps > previousReps && previousReps >= 0
    /** The one-line note on the plain set-done screen. */
    val line get() = when (kind) {
        "HEAVIEST_WEIGHT" -> "최고 중량 갱신"
        "SAME_WEIGHT_REPS" -> "같은 무게 횟수 갱신"
        "ESTIMATED_ONE_REP_MAX" -> "추정 1RM 갱신"
        "BEST_VOLUME" -> "볼륨 기록 갱신"
        else -> null
    }
    companion object {
        val KINDS = setOf("HEAVIEST_WEIGHT", "SAME_WEIGHT_REPS", "ESTIMATED_ONE_REP_MAX", "BEST_VOLUME")
        /** Reads the record extras of a state broadcast; null when there is no record for [setId]. */
        fun from(intent: android.content.Intent, setId: String): SetRecord? {
            val kind = intent.getStringExtra("recordKind").orEmpty()
            val target = intent.getStringExtra("recordSetId").orEmpty()
            if (kind !in KINDS || target.isBlank() || target != setId) return null
            fun double(key: String) = intent.getDoubleExtra(key, Double.NaN).takeIf { it.isFinite() && it >= 0.0 }
            fun int(key: String) = intent.getIntExtra(key, -1).takeIf { it >= 0 }
            return SetRecord(kind, double("recordWeightKg"), double("recordPreviousWeightKg"), int("recordReps"), int("recordPreviousReps"))
        }
    }
}

/** A finished set as the watch saw it. */
internal data class SetMoment(val sessionId: String, val setId: String, val exerciseId: String, val exerciseName: String,
    val setNumber: Int, val reps: Int?, val weightKg: Double?, val record: SetRecord? = null)

/**
 * Screens 3, 7 and 8: shown for 3.5 seconds the moment a set ends. The plain screen draws a check; a heavier
 * load compares two bars; more reps at the same load lines up one dot per rep.
 */
internal class SetDoneView(context: Context) : Grid432(context) {
    enum class Look { DONE, HEAVIEST, REPS }
    var look = Look.DONE
        private set
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    val clock = text(13f, WatchWorkoutFace.WHITE, medium = true)
    // 3 · set done
    val check = DrawnCheckView(context)
    val title = text(18f, WatchWorkoutFace.WHITE, bold = true)
    val detail = text(14f, WatchWorkoutFace.WHITE, medium = true)
    val recordLine = text(10f, WatchWorkoutFace.SAGE)
    // 7 · heaviest load, 8 · more reps at the same load
    val label = text(11f, WatchWorkoutFace.SAGE, bold = true).apply { letterSpacing = 1f / 22f }
    val previousValue = text(14f, WatchWorkoutFace.SOFT)
    val todayValue = text(20f, WatchWorkoutFace.WHITE, medium = true)
    val previousLabel = text(10f, WatchWorkoutFace.MUTED)
    val todayLabel = text(10f, WatchWorkoutFace.SAGE, bold = true)
    val delta = text(13f, WatchWorkoutFace.SAGE, medium = true)
    val load = text(15f, WatchWorkoutFace.SOFT)
    val count = text(56f, WatchWorkoutFace.WHITE, medium = true).apply { letterSpacing = -4f / 112f }
    val previousReps = text(11f, WatchWorkoutFace.SOFT)
    val exercise = text(9f, WatchWorkoutFace.SOFT)
    private var previousBar = 92f
    private var dots = 0
    private var litFrom = 0
    /** 0..1 progress of the record entrance: today's bar rises, then the new dots pop in one after another. */
    private var entrance = 1f
    private var entranceAnimator: ValueAnimator? = null

    /** Plays the entrance of a record screen; every other look, and reduced motion, shows the finished picture at once. */
    fun playEntrance() {
        entranceAnimator?.cancel()
        if (look == Look.DONE || Moment.reducedMotion(context)) { entrance = 1f; invalidate(); return }
        entrance = 0f
        entranceAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (look == Look.HEAVIEST) 700L else (520L + 55L * (dots - litFrom).coerceIn(1, 8))
            startDelay = 240L; interpolator = LinearInterpolator()
            addUpdateListener { entrance = it.animatedValue as Float; invalidate() }
            start()
        }
    }
    /** Freezes the entrance at [fraction] (0..1); used by previews and tests to look at single frames. */
    fun seekEntrance(fraction: Float) { entranceAnimator?.cancel(); entrance = fraction.coerceIn(0f, 1f); invalidate() }
    val entranceProgress: Float get() = entrance
    override fun onDetachedFromWindow() { entranceAnimator?.cancel(); entrance = 1f; super.onDetachedFromWindow() }

    init {
        setBackgroundColor(Color.BLACK); setWillNotDraw(false)
        placeText(clock, 116, 316, 63.9f)
        place(check, 164, 104, 268, 208)
        placeText(title, 40, 392, 266.3f)
        placeText(detail, 70, 362, 307.5f, fit = true)
        placeText(recordLine, 80, 352, 352.8f, fit = true)
        placeText(previousLabel, 106, 206, 322.8f); placeText(todayLabel, 226, 326, 322.8f)
        placeText(todayValue, 196, 356, 163.6f, fit = true)
        placeText(delta, 90, 342, 355.9f, fit = true)
        placeText(load, 80, 352, 139.2f, fit = true)
        placeText(count, 40, 392, 242.2f, fit = true)
        placeText(previousReps, 80, 352, 340.5f, fit = true)
    }

    fun bind(moment: SetMoment, clockText: String) {
        clock.text = clockText
        val record = moment.record
        look = when { record?.showsWeightBars == true -> Look.HEAVIEST; record?.showsRepDots == true -> Look.REPS; else -> Look.DONE }
        val done = look == Look.DONE
        listOf(check, title, detail).forEach { it.visibility = if (done) VISIBLE else GONE }
        recordLine.visibility = if (done && record?.line != null) VISIBLE else GONE
        listOf(previousValue, todayValue, previousLabel, todayLabel, delta).forEach { it.visibility = if (look == Look.HEAVIEST) VISIBLE else GONE }
        listOf(load, count, previousReps).forEach { it.visibility = if (look == Look.REPS) VISIBLE else GONE }
        label.visibility = if (done) GONE else VISIBLE
        exercise.visibility = label.visibility
        val finished = "${moment.exerciseName} · ${moment.setNumber}세트 완료"
        when (look) {
            Look.DONE -> {
                title.text = "${moment.setNumber}세트 완료"
                detail.text = SpannableStringBuilder().apply {
                    fun value(text: String) = append(text)
                    fun dot() {
                        val s = length; val flags = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE; append(" · ")
                        setSpan(RelativeSizeSpan(22f / 28f), s, length, flags)
                        setSpan(ForegroundColorSpan(WatchWorkoutFace.SOFT), s, length, flags); setSpan(TypefaceSpanCompat(Typeface.DEFAULT), s, length, flags)
                    }
                    moment.reps?.let { value("${it}회") }
                    moment.weightKg?.let { if (isNotEmpty()) dot(); value("${Moment.kg(it)}kg") }
                }
                detail.visibility = if (detail.text.isEmpty()) GONE else VISIBLE
                recordLine.text = record?.line.orEmpty()
                check.contentDescription = null
                contentDescription = listOfNotNull(title.text, detail.text.takeIf { it.isNotEmpty() }, record?.line).joinToString(", ")
            }
            Look.HEAVIEST -> {
                val today = requireNotNull(record?.weightKg); val before = requireNotNull(record?.previousWeightKg)
                placeText(label, 60, 372, 102.5f); label.text = "최고 중량 갱신"
                previousBar = barFor(today, before)
                placeText(previousValue, 106, 206, 300f - previousBar - 28.5f)
                previousValue.text = Moment.kg(before)
                todayValue.text = Moment.valueWithUnit(Moment.kg(today), "kg", 18f / 40f, WatchWorkoutFace.SOFT, gapEm = 3f / 40f)
                previousLabel.text = "이전"; todayLabel.text = "오늘"
                delta.text = Moment.withIcon(IconSpan.Kind.UP, "+${Moment.kg(today - before)}kg", 1f, 6f / 26f, WatchWorkoutFace.SAGE)
                placeText(exercise, 108, 324, 393.1f, fit = true); sizeUnits(exercise, 18f); exercise.text = finished
                contentDescription = "최고 중량 갱신, 이전 ${Moment.kg(before)}kg, 오늘 ${Moment.kg(today)}kg, $finished"
            }
            Look.REPS -> {
                val today = requireNotNull(record?.reps); val before = requireNotNull(record?.previousReps)
                placeText(label, 60, 372, 100.5f); label.text = "횟수 갱신"
                load.text = record?.weightKg?.let { "${Moment.kg(it)}kg" }.orEmpty()
                load.visibility = if (load.text.isEmpty()) GONE else VISIBLE
                count.text = Moment.valueWithUnit(today.toString(), "회", 34f / 112f, WatchWorkoutFace.SOFT, gapEm = 6f / 112f)
                dots = today; litFrom = before
                previousReps.text = "이전 ${before}회 · +${today - before}회"
                placeText(exercise, 104, 328, 388f, fit = true); sizeUnits(exercise, 19f); exercise.text = finished
                contentDescription = "횟수 갱신, ${load.text} ${today}회, 이전 ${before}회, $finished"
            }
        }
        invalidate()
    }

    /** Today's bar is always full height; the previous bar is shorter in proportion to the gain, so a small gain still shows. */
    private fun barFor(today: Double, before: Double): Float = (114f * (1f - 4.8f * ((today - before) / today).toFloat())).coerceIn(52f, 108f)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        Moment.drawRim(canvas, this, rim, null, WatchWorkoutFace.SAGE, 1f)
        val k = minOf(width, height) / 432f
        val ox = (width - 432f * k) / 2f; val oy = (height - 432f * k) / 2f
        when (look) {
            Look.HEAVIEST -> {
                fun bar(left: Float, h: Float, color: Int) {
                    fill.color = color
                    val r = 8f * k
                    val rect = RectF(ox + left * k, oy + (300f - h) * k, ox + (left + 76f) * k, oy + 300f * k)
                    // Rounded top corners only.
                    canvas.drawRoundRect(rect, r, r, fill)
                    canvas.drawRect(rect.left, rect.bottom - r, rect.right, rect.bottom, fill)
                }
                bar(118f, previousBar, Moment.PREVIOUS_BAR)
                // Today's bar rises in the first 60 percent, a touch past its height and back, like the design's "숙".
                val rise = (entrance / .6f).coerceIn(0f, 1f)
                val eased = 1f - (1f - rise) * (1f - rise) * (1f - rise)
                val overshoot = if (rise >= 1f) 0f else 0f
                bar(238f, (114f * eased + overshoot).coerceAtLeast(0f), WatchWorkoutFace.SAGE)
            }
            Look.REPS -> drawDots(canvas, k, ox, oy)
            Look.DONE -> Unit
        }
    }

    /** One dot per rep: the earlier best in bone, the reps added today in sage. */
    private fun drawDots(canvas: Canvas, k: Float, ox: Float, oy: Float) {
        if (dots <= 0) return
        val cy = oy + 303f * k
        if (dots > 40) {
            val left = 96f; val right = 336f; val split = left + (right - left) * litFrom / dots
            fill.color = WatchWorkoutFace.BONE
            canvas.drawRoundRect(RectF(ox + left * k, cy - 4 * k, ox + split * k, cy + 4 * k), 4 * k, 4 * k, fill)
            fill.color = WatchWorkoutFace.SAGE
            canvas.drawRoundRect(RectF(ox + (split + 4) * k, cy - 4 * k, ox + right * k, cy + 4 * k), 4 * k, 4 * k, fill)
            return
        }
        val pitch = if (dots == 1) 0f else minOf(24f, 240f / (dots - 1))
        val size = if (dots == 1) 14f else minOf(14f, pitch * .6f)
        val first = 216f - pitch * (dots - 1) / 2f
        val added = (dots - litFrom).coerceAtLeast(1)
        for (i in 0 until dots) {
            val isNew = i >= litFrom
            // The earlier dots fade in together in the first 25 percent; each new dot then pops in turn.
            val local = if (!isNew) (entrance / .25f).coerceIn(0f, 1f)
                else ((entrance - .3f) / .7f * (added + 1) - (i - litFrom)).coerceIn(0f, 1f)
            if (local <= 0f) continue
            // Ease out with one overshoot so the new dots go "똥".
            val scale = if (!isNew) local else {
                val t = local - 1f
                1f + 2.4f * t * t * t + 1.4f * t * t
            }.coerceIn(0f, 1.35f)
            fill.color = if (isNew) WatchWorkoutFace.SAGE else WatchWorkoutFace.BONE
            fill.alpha = (255 * minOf(1f, local * 2f)).toInt()
            canvas.drawCircle(ox + (first + i * pitch) * k, cy, size / 2f * k * scale, fill)
        }
        fill.alpha = 255
    }
}

/** Number of countdown steps for a hold of [targetMs]: one per started second. */
internal fun holdSteps(targetMs: Long): Int = ceil(targetMs / 1000.0).toInt().coerceAtLeast(1)
