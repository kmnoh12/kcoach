package dev.kyungmin.kcoach.wear

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface

/**
 * Columns of a value over its label, centred as one group with hairlines between them (the design's flex rows
 * on screens 11 and 12). Missing columns are simply left out, so the rest stay centred.
 */
internal class StatRow(private val host: Grid432, private val valueUnits: Float, private val labelUnits: Float,
    private val top: Float, private val labelGap: Float, private val gap: Float, private val ruleHeight: Float, private val maxWidth: Float) {
    val values = List(3) { host.text(valueUnits / 2f, WatchWorkoutFace.WHITE, medium = true) }
    val labels = List(3) { host.text(labelUnits / 2f, WatchWorkoutFace.MUTED) }
    private var rules = FloatArray(0)
    private val rulePaint = Paint().apply { color = Moment.DIVIDER }
    var shown = 0
        private set

    fun bind(items: List<Pair<String, String>>) {
        shown = items.size.coerceAtMost(3)
        val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); textSize = valueUnits }
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = labelUnits }
        val widths = items.take(3).map { (v, k) -> maxOf(valuePaint.measureText(v), labelPaint.measureText(k)) }
        val total = widths.sum() + (shown - 1).coerceAtLeast(0) * (2 * gap + 1.5f)
        val shrink = if (total > maxWidth) maxWidth / total else 1f
        var x = 216f - total * shrink / 2f
        val ruleXs = mutableListOf<Float>()
        for (i in 0 until 3) {
            val visible = i < shown
            values[i].visibility = if (visible) android.view.View.VISIBLE else android.view.View.GONE
            labels[i].visibility = values[i].visibility
            if (!visible) continue
            val w = widths[i] * shrink
            val centre = x + w / 2f
            val half = (w / 2f + 6f).toInt() + 1
            values[i].text = items[i].first; labels[i].text = items[i].second
            host.sizeUnits(values[i], valueUnits * shrink); host.sizeUnits(labels[i], labelUnits * shrink)
            host.placeText(values[i], centre.toInt() - half, centre.toInt() + half, top + .841f * valueUnits)
            host.placeText(labels[i], centre.toInt() - half, centre.toInt() + half, top + valueUnits + labelGap + .841f * labelUnits)
            x += w
            if (i < shown - 1) { x += gap * shrink; ruleXs += x + .75f * shrink; x += (1.5f + gap) * shrink }
        }
        rules = ruleXs.toFloatArray()
        host.invalidate()
    }

    fun draw(canvas: Canvas, k: Float, ox: Float, oy: Float) {
        if (shown == 0) return
        val rowHeight = valueUnits + labelGap + labelUnits
        val t = top + (rowHeight - ruleHeight) / 2f
        rules.forEach { x -> canvas.drawRect(ox + (x - .75f) * k, oy + t * k, ox + (x + .75f) * k, oy + (t + ruleHeight) * k, rulePaint) }
    }
}

/**
 * Calibration on the watch: screen 9 (hold still while a ring empties and the seconds count down), screen 10
 * (reps against the calibration target, with the rim and segments filling) and screen 11 (the drawn check with
 * the sensor count next to the phone's confirmed count).
 */
internal class CalibrationView(context: Context) : Grid432(context) {
    enum class Stage { PREPARE, COUNT, DONE }
    var stage = Stage.PREPARE
        private set
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val clock = text(13f, WatchWorkoutFace.WHITE, medium = true)
    val title = text(11f, WatchWorkoutFace.WHITE, bold = true).apply { letterSpacing = .5f / 22f }
    val countdown = RollingNumberView(context, 128f, 107.6f - 3f, 128f, 0f, WatchWorkoutFace.SAGE)
    val waiting = text(21f, WatchWorkoutFace.SAGE, medium = true)
    val count = RollingNumberView(context, 170f, 142f, 176f, -4f, WatchWorkoutFace.SAGE)
    val target = text(15f, WatchWorkoutFace.SOFT)
    val message = text(11.5f, WatchWorkoutFace.SOFT)
    val footer = text(9.5f, WatchWorkoutFace.MUTED)
    val check = DrawnCheckView(context)
    val heading = text(18f, WatchWorkoutFace.WHITE, bold = true)
    val stats = StatRow(this, 38f, 18f, 262f, 8f, 26f, 46f, 240f)
    val verdict = text(10.5f, WatchWorkoutFace.SAGE, bold = true)
    val hint = text(9.5f, WatchWorkoutFace.MUTED)
    /** Rim (count) or orb (countdown) fill, animated alongside the number. */
    private var fill = 1f
    private var fillAnimator: ValueAnimator? = null
    private var segments = 0
    private var litSegments = 0
    private var pendingLit: Runnable? = null

    init {
        setBackgroundColor(Color.BLACK); setWillNotDraw(false)
        placeText(clock, 116, 316, 63.9f)
        placeText(title, 60, 372, 94.5f, fit = true)
        place(countdown, 141, 152, 291, 280)
        placeText(waiting, 126, 306, 228f)
        place(count, 72, 112, 276, 282)
        place(check, 164, 92, 268, 196)
        placeText(heading, 40, 392, 234.3f)
        placeText(verdict, 80, 352, 353.7f)
        placeText(hint, 90, 342, 388f, fit = true)
    }

    private fun show(stage: Stage) {
        val changed = stage != this.stage
        this.stage = stage
        val prepare = stage == Stage.PREPARE; val counting = stage == Stage.COUNT; val done = stage == Stage.DONE
        countdown.visibility = if (prepare) VISIBLE else GONE
        listOf(count, target).forEach { it.visibility = if (counting) VISIBLE else GONE }
        listOf(title, message).forEach { it.visibility = if (done) GONE else VISIBLE }
        listOf(check, heading, verdict, hint).forEach { it.visibility = if (done) VISIBLE else GONE }
        if (done) footer.visibility = GONE
        if (!done) stats.bind(emptyList())
        if (!prepare) waiting.visibility = GONE
        if (changed) { fillAnimator?.cancel(); pendingLit?.let(::removeCallbacks); pendingLit = null }
    }

    private fun animateFill(to: Float, animate: Boolean) {
        if (to == fill && fillAnimator?.isRunning != true) return
        fillAnimator?.cancel()
        if (!animate || Moment.reducedMotion(context)) { fill = to; invalidate(); return }
        fillAnimator = ValueAnimator.ofFloat(fill, to).apply {
            duration = Moment.ROLL_MS; interpolator = Moment.EASE
            addUpdateListener { fill = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun footer(cameraRecording: Boolean, baseline: Float) {
        footer.visibility = if (cameraRecording) VISIBLE else GONE
        footer.text = Moment.withIcon(IconSpan.Kind.CAMERA, "폰 카메라 촬영 중", 1f, 8f / 19f, WatchWorkoutFace.MUTED)
        placeText(footer, 90, 342, baseline, fit = true)
    }

    /** Screen 9. [secondsLeft] counts down while the wrist is held still; null when the hold has not been reported. */
    fun bindPrepare(clockText: String, secondsLeft: Int?, steps: Int, cameraRecording: Boolean) {
        val fresh = stage != Stage.PREPARE
        show(Stage.PREPARE)
        clock.text = clockText; title.text = "보정"
        message.text = "시작 자세로 가만히 있어요"; placeText(message, 70, 362, 353.3f, fit = true)
        footer(cameraRecording, 388f)
        if (secondsLeft == null) {
            countdown.visibility = GONE; waiting.visibility = VISIBLE; waiting.text = "준비"
            animateFill(1f, false)
        } else {
            countdown.visibility = VISIBLE; waiting.visibility = GONE
            countdown.setNumber(secondsLeft.toString(), animate = !fresh)
            animateFill(secondsLeft.toFloat() / steps.coerceAtLeast(1), animate = !fresh)
        }
        invalidate()
    }

    /** Screen 10. */
    fun bindCount(clockText: String, reps: Int, targetReps: Int, cameraRecording: Boolean) {
        val fresh = stage != Stage.COUNT
        show(Stage.COUNT)
        val goal = targetReps.coerceAtLeast(1)
        clock.text = clockText; title.text = "보정 · 목표 ${goal}회"
        count.setNumber(reps.toString(), animate = !fresh)
        target.text = "/ ${goal}회"
        // The design centres the 204-wide number window, a 14 gap and the "/ 5회" text as one group.
        val ofWidth = Paint().apply { textSize = 30f }.measureText(target.text.toString())
        val left = (216f - (204f + 14f + ofWidth) / 2f).toInt()
        place(count, left, 112, left + 204, 282)
        placeText(target, left + 218 - 6, left + 218 + ofWidth.toInt() + 6, 245.2f)
        message.text = "평소처럼 천천히 해요"; placeText(message, 70, 362, 345.3f, fit = true)
        footer(cameraRecording, 380f)
        animateFill((reps.toFloat() / goal).coerceIn(0f, 1f), animate = !fresh)
        segments = goal
        val lit = reps.coerceIn(0, goal)
        pendingLit?.let(::removeCallbacks); pendingLit = null
        // A segment lights when the rolling number lands, like the design's stepped fill.
        if (fresh || lit < litSegments || Moment.reducedMotion(context)) litSegments = lit
        else if (lit != litSegments) pendingLit = Runnable { litSegments = lit; pendingLit = null; invalidate() }.also { postDelayed(it, Moment.ROLL_MS) }
        invalidate()
    }

    /** Screen 11. [confirmed] is the phone's own count; without it only the sensor count is shown. */
    fun bindDone(clockText: String, sensorReps: Int, confirmed: Int?, playCheck: Boolean) {
        show(Stage.DONE)
        clock.text = clockText; heading.text = "보정 완료"
        stats.bind(listOfNotNull("${sensorReps}회" to "센서", confirmed?.let { "${it}회" to "확인" }))
        verdict.visibility = if (confirmed == null) GONE else VISIBLE
        if (confirmed == sensorReps) { verdict.text = "일치"; verdict.setTextColor(WatchWorkoutFace.SAGE) }
        else if (confirmed != null) { verdict.text = "${kotlin.math.abs(confirmed - sensorReps)}회 차이"; verdict.setTextColor(WatchWorkoutFace.ORANGE) }
        hint.text = "폰에서 결과를 확인해요"
        if (playCheck) check.play()
        fill = 1f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val k = minOf(width, height) / 432f
        val ox = (width - 432f * k) / 2f; val oy = (height - 432f * k) / 2f
        when (stage) {
            Stage.PREPARE -> {
                Moment.drawRim(canvas, this, paint, Moment.TRACK, Moment.TRACK, 0f)
                // The orb: radius 100, stroke 6, emptying clockwise from 12 o'clock as the hold completes.
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 6f * k; paint.strokeCap = Paint.Cap.ROUND
                val r = 100f * k; val cx = ox + 216f * k; val cy = oy + 216f * k
                paint.color = Moment.ORB_TRACK; canvas.drawCircle(cx, cy, r, paint)
                paint.color = WatchWorkoutFace.SAGE
                if (fill >= .999f) canvas.drawCircle(cx, cy, r, paint)
                else if (fill > .001f) canvas.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), -90f, 360f * fill, false, paint)
            }
            Stage.COUNT -> {
                Moment.drawRim(canvas, this, paint, Moment.TRACK, WatchWorkoutFace.SAGE, fill)
                drawSegments(canvas, k, ox, oy)
            }
            Stage.DONE -> {
                Moment.drawRim(canvas, this, paint, null, WatchWorkoutFace.SAGE, 1f)
                stats.draw(canvas, k, ox, oy)
            }
        }
    }

    /** One cell per target rep between x 96 and 336; a long target becomes one continuous bar. */
    private fun drawSegments(canvas: Canvas, k: Float, ox: Float, oy: Float) {
        if (segments <= 0) return
        paint.style = Paint.Style.FILL
        val top = oy + 296f * k; val bottom = oy + 304f * k
        if (segments > 12) {
            paint.color = Moment.SEGMENT_OFF; canvas.drawRoundRect(RectF(ox + 96 * k, top, ox + 336 * k, bottom), 4 * k, 4 * k, paint)
            val end = 96f + 240f * litSegments / segments
            if (litSegments > 0) { paint.color = WatchWorkoutFace.SAGE; canvas.drawRoundRect(RectF(ox + 96 * k, top, ox + end * k, bottom), 4 * k, 4 * k, paint) }
            return
        }
        val gap = 8f; val w = (240f - gap * (segments - 1)) / segments
        for (i in 0 until segments) {
            paint.color = if (i < litSegments) WatchWorkoutFace.SAGE else Moment.SEGMENT_OFF
            val l = 96f + i * (w + gap)
            canvas.drawRoundRect(RectF(ox + l * k, top, ox + (l + w) * k, bottom), 4 * k, 4 * k, paint)
        }
    }
}
