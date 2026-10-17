package dev.kyungmin.kcoach.wear

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable

/**
 * Screen 12, the workout result: time on top, then sets, volume and average heart rate in one row, today's
 * records, and the button that opens the full result on the phone. Values the watch does not know are left out.
 */
internal class WorkoutResultView(context: Context) : Grid432(context) {
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG)
    val clock = text(13f, WatchWorkoutFace.WHITE, medium = true)
    val heading = text(14f, WatchWorkoutFace.WHITE, bold = true)
    val time = text(44f, WatchWorkoutFace.WHITE, medium = true).apply { letterSpacing = -4f / 88f }
    val timeLabel = text(9f, WatchWorkoutFace.MUTED)
    val stats = StatRow(this, 34f, 16f, 234f, 6f, 22f, 42f, 300f)
    val note = text(10.5f, WatchWorkoutFace.SAGE, medium = true)
    /** The tappable area is taller than the drawn 42-unit button so it is easy to hit. */
    val open = text(9.5f, Moment.ON_BONE, bold = true).apply { isClickable = true; isFocusable = true; tag = "phone-result" }
    var onOpen: () -> Unit = {}

    init {
        setBackgroundColor(Color.BLACK); setWillNotDraw(false)
        placeText(clock, 116, 316, 63.9f)
        placeText(heading, 60, 372, 95.5f)
        placeText(time, 40, 392, 180f, fit = true)
        placeText(timeLabel, 100, 332, 215.1f)
        placeText(note, 76, 356, 320f, fit = true)
        // Drawn 110..322 x 338..380; touchable 328..390, centred on the same line so the label stays centred in the pill.
        place(open, 110, 328, 322, 390)
        open.setOnClickListener { onOpen() }
        heading.text = Moment.withIcon(IconSpan.Kind.CHECK_CIRCLE, "운동 완료", 30f / 28f, 10f / 28f, WatchWorkoutFace.SAGE)
        heading.contentDescription = "운동 완료"
        timeLabel.text = "운동 시간"
        open.text = "폰에서 자세히 보기"
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val k = minOf(w, h) / 432f
        open.background = InsetDrawable(GradientDrawable().apply { setColor(WatchWorkoutFace.BONE); cornerRadius = 12f * k },
            0, (10f * k).toInt(), 0, (10f * k).toInt())
    }

    fun bind(clockText: String, summary: WatchHomeStore.Summary?, status: String, statusColor: Int, pending: Boolean) {
        clock.text = clockText
        time.text = summary?.elapsedMs?.let(::duration) ?: "—"
        stats.bind(listOfNotNull(
            summary?.sets?.let { it.toString() to "세트" },
            summary?.volumeKg?.let { "%,d".format(java.util.Locale.US, Math.round(it)) to "볼륨 kg" },
            summary?.averageHeartRate?.let { it.toString() to "평균 심박" },
        ))
        val records = summary?.recordsBroken?.takeIf { it > 0 }
        when {
            status.isNotBlank() -> { note.text = status; note.setTextColor(statusColor); note.visibility = VISIBLE }
            records != null -> {
                note.text = Moment.withIcon(IconSpan.Kind.UP, "오늘 깬 기록 ${records}개", 1f, 6f / 21f, WatchWorkoutFace.SAGE)
                note.setTextColor(WatchWorkoutFace.SAGE); note.visibility = VISIBLE
            }
            else -> note.visibility = GONE
        }
        open.isEnabled = !pending; open.alpha = if (pending) .5f else 1f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        Moment.drawRim(canvas, this, rim, null, WatchWorkoutFace.SAGE, 1f)
        val k = minOf(width, height) / 432f
        stats.draw(canvas, k, (width - 432f * k) / 2f, (height - 432f * k) / 2f)
    }

    companion object {
        fun duration(ms: Long): String {
            val s = (ms / 1000L).coerceAtLeast(0L)
            return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
        }
    }
}
