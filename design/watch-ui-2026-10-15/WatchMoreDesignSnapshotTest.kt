package dev.kyungmin.kcoach.wear

import android.content.Context
import android.graphics.*
import android.view.View
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Native rendering of design proposals only. This class is not included in the app. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w216dp-h216dp-round-xhdpi")
class WatchMoreDesignSnapshotTest {
    @Test fun renderFourAdditionalWorkoutLayouts() {
        val output = File("build/outputs/watch-design").apply { mkdirs() }
        fun save(bitmap: Bitmap, name: String) = File(output, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        val context = RuntimeEnvironment.getApplication()
        val images = (0..3).map { variant ->
            val view = MoreDesignView(context, variant)
            val exact = View.MeasureSpec.makeMeasureSpec(432, View.MeasureSpec.EXACTLY)
            view.measure(exact, exact); view.layout(0, 0, 432, 432)
            Bitmap.createBitmap(432, 432, Bitmap.Config.ARGB_8888).also {
                view.draw(Canvas(it)); save(it, "draft-${'d' + variant}")
            }
        }
        val board = Bitmap.createBitmap(1000, 1180, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(board)
        canvas.drawColor(Color.rgb(24, 26, 29))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 32f; typeface = Typeface.DEFAULT_BOLD }
        canvas.drawText("워치 운동 화면 · 추가 시안 4개", 40f, 48f, paint)
        paint.textSize = 22f; paint.color = Color.rgb(183, 190, 199); paint.typeface = Typeface.DEFAULT
        canvas.drawText("덤벨 컬 · 1세트 · 10kg · 8 / 목표 20회 · 심박 112", 40f, 82f, paint)
        val names = listOf("D · 큰 숫자와 하단 요약", "E · 목표까지 남은 횟수", "F · 외곽 진행 표시", "G · 3칸 정보")
        images.forEachIndexed { index, bitmap ->
            val x = if (index % 2 == 0) 52f else 516f
            val y = if (index < 2) 154f else 660f
            paint.textSize = 25f; paint.color = Color.WHITE; paint.typeface = Typeface.DEFAULT_BOLD
            canvas.drawText(names[index], x + 12f, y - 22f, paint)
            canvas.save()
            canvas.clipPath(Path().apply { addCircle(x + 216f, y + 216f, 214f, Path.Direction.CW) })
            canvas.drawBitmap(bitmap, x, y, null)
            canvas.restore()
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 1f; paint.color = Color.rgb(73, 78, 87)
            canvas.drawCircle(x + 216f, y + 216f, 214f, paint)
            paint.style = Paint.Style.FILL
        }
        paint.color = Color.rgb(183, 190, 199); paint.typeface = Typeface.DEFAULT; paint.textSize = 21f
        canvas.drawText("같은 상태를 다른 배치로 비교 · E의 12 = 20 − 8 · 앱 미적용", 40f, 1144f, paint)
        save(board, "comparison-more")
        board.recycle(); images.forEach { it.recycle() }
    }
}

private class MoreDesignView(context: Context, private val variant: Int) : View(context) {
    private val white = Color.rgb(245, 247, 250)
    private val muted = Color.rgb(164, 177, 190)
    private val green = Color.rgb(0, 230, 140)
    private val red = Color.rgb(255, 105, 105)
    private val neutral = Color.rgb(48, 56, 64)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private fun text(canvas: Canvas, value: String, x: Float, baseline: Float, sp: Float,
        color: Int = white, bold: Boolean = false) {
        paint.style = Paint.Style.FILL; paint.color = color; paint.textAlign = Paint.Align.CENTER
        paint.textSize = sp * resources.displayMetrics.scaledDensity
        paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        paint.fontFeatureSettings = "tnum"
        canvas.drawText(value, x, baseline, paint)
    }
    private fun line(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float) {
        paint.color = neutral; paint.style = Paint.Style.STROKE; paint.strokeWidth = 1f
        canvas.drawLine(x1, y1, x2, y2, paint); paint.style = Paint.Style.FILL
    }
    private fun more(canvas: Canvas, label: Boolean = false) {
        if (label) text(canvas, "더 보기", 216f, 387f, 11f, muted)
        paint.color = muted; paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.5f; paint.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(210f, 402f, 216f, 407f, paint)
        canvas.drawLine(216f, 407f, 222f, 402f, paint)
        paint.style = Paint.Style.FILL
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)
        when (variant) {
            0 -> {
                text(canvas, "10:09", 216f, 38f, 12f, muted)
                text(canvas, "덤벨 컬", 216f, 85f, 16f, bold = true)
                text(canvas, "1세트 · 자동 측정", 216f, 122f, 12f, muted)
                text(canvas, "8", 175f, 252f, 48f, green, true)
                text(canvas, "/ 20회", 284f, 249f, 14f, muted)
                line(canvas, 93f, 279f, 339f, 279f)
                text(canvas, "중량", 134f, 309f, 12f, muted)
                text(canvas, "심박", 294f, 309f, 12f, muted)
                text(canvas, "10", 127f, 354f, 22f, white, true)
                text(canvas, "kg", 177f, 352f, 12f, muted)
                text(canvas, "112", 287f, 354f, 22f, red, true)
                text(canvas, "bpm", 287f, 380f, 12f, muted)
                more(canvas)
            }
            1 -> {
                text(canvas, "10:09", 216f, 38f, 12f, muted)
                text(canvas, "덤벨 컬", 216f, 85f, 16f, bold = true)
                text(canvas, "1세트 · 10kg", 216f, 122f, 12f, muted)
                text(canvas, "목표까지", 216f, 174f, 12f, muted)
                text(canvas, "12", 216f, 268f, 44f, green, true)
                text(canvas, "회 남음", 216f, 304f, 12f)
                text(canvas, "현재 8회 · 목표 20회", 216f, 345f, 12f, muted)
                text(canvas, "♥ 112 bpm", 216f, 383f, 12f, red)
                more(canvas)
            }
            2 -> {
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 7f; paint.strokeCap = Paint.Cap.ROUND
                paint.color = neutral
                val ring = RectF(18f, 18f, 414f, 414f)
                canvas.drawArc(ring, -90f, 360f, false, paint)
                paint.color = green
                canvas.drawArc(ring, -90f, 360f * 8f / 20f, false, paint)
                paint.style = Paint.Style.FILL
                text(canvas, "10:09", 216f, 55f, 12f, muted)
                text(canvas, "덤벨 컬", 216f, 104f, 16f, bold = true)
                text(canvas, "1세트 · 10kg", 216f, 141f, 12f, muted)
                text(canvas, "자동 횟수", 216f, 188f, 12f, muted)
                text(canvas, "8", 216f, 277f, 42f, green, true)
                text(canvas, "목표 20회", 216f, 313f, 12f, muted)
                text(canvas, "♥ 112 bpm", 216f, 362f, 12f, red)
                more(canvas)
            }
            else -> {
                text(canvas, "10:09", 216f, 38f, 12f, muted)
                text(canvas, "덤벨 컬", 216f, 85f, 16f, bold = true)
                text(canvas, "1세트", 216f, 122f, 12f, muted)
                text(canvas, "자동 횟수", 126f, 181f, 12f, muted)
                text(canvas, "8", 126f, 274f, 44f, green, true)
                text(canvas, "목표 20회", 126f, 316f, 12f, muted)
                line(canvas, 220f, 149f, 220f, 348f)
                text(canvas, "중량", 299f, 174f, 12f, muted)
                text(canvas, "10kg", 299f, 220f, 20f, white, true)
                line(canvas, 247f, 244f, 351f, 244f)
                text(canvas, "심박", 299f, 278f, 12f, muted)
                text(canvas, "112", 299f, 324f, 20f, red, true)
                text(canvas, "bpm", 299f, 349f, 12f, muted)
                more(canvas, label = true)
            }
        }
    }
}
