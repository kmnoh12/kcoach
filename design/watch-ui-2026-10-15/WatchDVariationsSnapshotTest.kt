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

/** Four visual variations on the user's selected D direction; no app behavior is changed. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w216dp-h216dp-round-xhdpi")
class WatchDVariationsSnapshotTest {
    @Test fun renderD1WithoutBpmAndVerifyTargetColorBoundary() {
        val output = File("build/outputs/watch-design").apply { mkdirs() }
        fun render(reps: Int, target: Int = 20): Bitmap {
            val view = DVariationView(RuntimeEnvironment.getApplication(), 0, reps, target)
            val exact = View.MeasureSpec.makeMeasureSpec(432, View.MeasureSpec.EXACTLY)
            view.measure(exact, exact); view.layout(0, 0, 432, 432)
            return Bitmap.createBitmap(432, 432, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        fun save(bitmap: Bitmap, name: String) = File(output, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        fun containsCountColor(bitmap: Bitmap, color: Int): Boolean =
            (165..260).any { y -> (75..240).any { x -> bitmap.getPixel(x, y) == color } }
        val green = Color.rgb(0, 230, 140)
        val orange = Color.rgb(255, 152, 0)
        val images = listOf(19, 20, 21).map { render(it) }
        images.forEachIndexed { index, bitmap ->
            val above = index == 2
            org.junit.Assert.assertTrue(containsCountColor(bitmap, if (above) orange else green))
            org.junit.Assert.assertFalse(containsCountColor(bitmap, if (above) green else orange))
        }
        val differentTarget = render(13, 12)
        org.junit.Assert.assertTrue(containsCountColor(differentTarget, orange))
        differentTarget.recycle()

        val revised = render(8)
        val original = requireNotNull(BitmapFactory.decodeFile("../design/watch-ui-2026-10-15/draft-d1.png"))
        // Every pixel outside the removed bpm label stays identical, including the heart-rate digits.
        for (y in 0 until 432) for (x in 0 until 432) {
            if (x in 250..325 && y in 355..390) continue
            org.junit.Assert.assertEquals("Only bpm should be removed: ($x,$y)", original.getPixel(x, y), revised.getPixel(x, y))
        }
        original.recycle()
        save(revised, "draft-d1-revised")
        save(images[1], "draft-d1-at-target")
        save(images[2], "draft-d1-over-target")

        val board = Bitmap.createBitmap(1000, 650, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(board)
        canvas.drawColor(Color.rgb(24, 26, 29))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 32f; typeface = Typeface.DEFAULT_BOLD }
        canvas.drawText("D1 수정 · 목표 초과 색상", 40f, 48f, paint)
        paint.textSize = 22f; paint.color = Color.rgb(183, 190, 199); paint.typeface = Typeface.DEFAULT
        canvas.drawText("bpm 글자만 제거 · 심박 112의 위치 유지 · 목표 20회", 40f, 82f, paint)
        listOf(images[1], images[2]).forEachIndexed { index, bitmap ->
            val x = if (index == 0) 52f else 516f
            paint.textSize = 25f; paint.color = Color.WHITE; paint.typeface = Typeface.DEFAULT_BOLD
            canvas.drawText(if (index == 0) "20회 · 목표까지 초록" else "21회 · 초과하면 주황", x + 12f, 132f, paint)
            canvas.save()
            canvas.clipPath(Path().apply { addCircle(x + 216f, 370f, 214f, Path.Direction.CW) })
            canvas.drawBitmap(bitmap, x, 154f, null)
            canvas.restore()
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 1f; paint.color = Color.rgb(73, 78, 87)
            canvas.drawCircle(x + 216f, 370f, 214f, paint)
            paint.style = Paint.Style.FILL
        }
        paint.textSize = 21f; paint.color = Color.rgb(183, 190, 199); paint.typeface = Typeface.DEFAULT
        canvas.drawText("비교용 시안 · 앱 미적용", 40f, 626f, paint)
        save(board, "comparison-d1-revised")
        board.recycle(); revised.recycle(); images.forEach { it.recycle() }
    }

    @Test fun renderD2WithoutBpmAndVerifyTargetColorBoundary() {
        val output = File("build/outputs/watch-design").apply { mkdirs() }
        fun render(reps: Int, target: Int = 20): Bitmap {
            val view = DVariationView(RuntimeEnvironment.getApplication(), 1, reps, target)
            val exact = View.MeasureSpec.makeMeasureSpec(432, View.MeasureSpec.EXACTLY)
            view.measure(exact, exact); view.layout(0, 0, 432, 432)
            return Bitmap.createBitmap(432, 432, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        fun save(bitmap: Bitmap, name: String) = File(output, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        fun containsCountColor(bitmap: Bitmap, color: Int): Boolean =
            (165..260).any { y -> (75..240).any { x -> bitmap.getPixel(x, y) == color } }
        val green = Color.rgb(0, 230, 140)
        val orange = Color.rgb(255, 152, 0)
        val images = listOf(19, 20, 21).map { render(it) }
        images.forEachIndexed { index, bitmap ->
            val above = index == 2
            org.junit.Assert.assertTrue(containsCountColor(bitmap, if (above) orange else green))
            org.junit.Assert.assertFalse(containsCountColor(bitmap, if (above) green else orange))
        }
        val differentTarget = render(13, 12)
        org.junit.Assert.assertTrue(containsCountColor(differentTarget, orange))
        differentTarget.recycle()

        val revised = render(8)
        val original = requireNotNull(BitmapFactory.decodeFile("../design/watch-ui-2026-10-15/draft-d2.png"))
        // Every pixel outside the removed bpm label stays identical, including the heart-rate digits.
        for (y in 0 until 432) for (x in 0 until 432) {
            if (x in 270..344 && y in 340..380) continue
            org.junit.Assert.assertEquals("Only bpm should be removed: ($x,$y)", original.getPixel(x, y), revised.getPixel(x, y))
        }
        original.recycle()
        save(revised, "draft-d2-revised")
        save(images[1], "draft-d2-at-target")
        save(images[2], "draft-d2-over-target")

        val board = Bitmap.createBitmap(1000, 650, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(board)
        canvas.drawColor(Color.rgb(24, 26, 29))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 32f; typeface = Typeface.DEFAULT_BOLD }
        canvas.drawText("D2 수정 · 목표 초과 색상", 40f, 48f, paint)
        paint.textSize = 22f; paint.color = Color.rgb(183, 190, 199); paint.typeface = Typeface.DEFAULT
        canvas.drawText("bpm 글자만 제거 · 심박 112의 위치 유지 · 목표 20회", 40f, 82f, paint)
        listOf(images[1], images[2]).forEachIndexed { index, bitmap ->
            val x = if (index == 0) 52f else 516f
            paint.textSize = 25f; paint.color = Color.WHITE; paint.typeface = Typeface.DEFAULT_BOLD
            canvas.drawText(if (index == 0) "20회 · 목표까지 초록" else "21회 · 초과하면 주황", x + 12f, 132f, paint)
            canvas.save()
            canvas.clipPath(Path().apply { addCircle(x + 216f, 370f, 214f, Path.Direction.CW) })
            canvas.drawBitmap(bitmap, x, 154f, null)
            canvas.restore()
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 1f; paint.color = Color.rgb(73, 78, 87)
            canvas.drawCircle(x + 216f, 370f, 214f, paint)
            paint.style = Paint.Style.FILL
        }
        paint.textSize = 21f; paint.color = Color.rgb(183, 190, 199); paint.typeface = Typeface.DEFAULT
        canvas.drawText("비교용 시안 · 앱 미적용", 40f, 626f, paint)
        save(board, "comparison-d2-revised")
        board.recycle(); revised.recycle(); images.forEach { it.recycle() }
    }

    @Test fun renderFourVariationsOfD() {
        val output = File("build/outputs/watch-design").apply { mkdirs() }
        fun save(bitmap: Bitmap, name: String) = File(output, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        val images = (0..3).map { variant ->
            val view = DVariationView(RuntimeEnvironment.getApplication(), variant)
            val exact = View.MeasureSpec.makeMeasureSpec(432, View.MeasureSpec.EXACTLY)
            view.measure(exact, exact); view.layout(0, 0, 432, 432)
            Bitmap.createBitmap(432, 432, Bitmap.Config.ARGB_8888).also {
                view.draw(Canvas(it)); save(it, "draft-d${variant + 1}")
            }
        }
        val board = Bitmap.createBitmap(1000, 1180, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(board)
        canvas.drawColor(Color.rgb(24, 26, 29))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 32f; typeface = Typeface.DEFAULT_BOLD }
        canvas.drawText("D를 바탕으로 · 조정 시안 4개", 40f, 48f, paint)
        paint.textSize = 22f; paint.color = Color.rgb(183, 190, 199); paint.typeface = Typeface.DEFAULT
        canvas.drawText("덤벨 컬 · 1세트 · 10kg · 8 / 목표 20회 · 심박 112", 40f, 82f, paint)
        val names = listOf("D1 · 목표 표시 명확하게", "D2 · 하단 요약 간결하게", "D3 · 보조 숫자 조금 크게", "D4 · 세트와 횟수 묶기")
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
        canvas.drawText("D의 큰 횟수 + 하단 중량·심박 유지 · 비교용 예시 · 앱 미적용", 40f, 1144f, paint)
        save(board, "comparison-d-variations")
        board.recycle(); images.forEach { it.recycle() }
    }
}

private class DVariationView(context: Context, private val variant: Int,
    private val reps: Int = 8, private val targetReps: Int = 20) : View(context) {
    private val white = Color.rgb(245, 247, 250)
    private val muted = Color.rgb(164, 177, 190)
    private val green = Color.rgb(0, 230, 140)
    private val orange = Color.rgb(255, 152, 0)
    private val red = Color.rgb(255, 105, 105)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private fun text(canvas: Canvas, value: String, x: Float, baseline: Float, sp: Float,
        color: Int = white, bold: Boolean = false) {
        paint.style = Paint.Style.FILL; paint.color = color; paint.textAlign = Paint.Align.CENTER
        paint.textSize = sp * resources.displayMetrics.scaledDensity
        paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        paint.fontFeatureSettings = "tnum"
        canvas.drawText(value, x, baseline, paint)
    }
    private fun divider(canvas: Canvas, y: Float) {
        paint.color = Color.rgb(48, 56, 64); paint.style = Paint.Style.STROKE; paint.strokeWidth = 1f
        canvas.drawLine(93f, y, 339f, y, paint); paint.style = Paint.Style.FILL
    }
    private fun bottom(canvas: Canvas, labelsY: Float, valuesY: Float, size: Float, showBpm: Boolean = true) {
        text(canvas, "중량", 134f, labelsY, 12f, muted)
        text(canvas, "심박", 294f, labelsY, 12f, muted)
        text(canvas, "10", 127f, valuesY, size, white, true)
        text(canvas, "kg", 181f, valuesY - 2f, 12f, muted)
        text(canvas, "112", 287f, valuesY, size, red, true)
        if (showBpm) text(canvas, "bpm", 287f, valuesY + 26f, 12f, muted)
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)
        text(canvas, "10:09", 216f, 38f, 12f, muted)
        text(canvas, "덤벨 컬", 216f, 85f, 16f, bold = true)
        text(canvas, if (variant == 3) "자동 측정" else "1세트 · 자동 측정", 216f, 122f, 12f, muted)
        when (variant) {
            0 -> {
                text(canvas, reps.toString(), 175f, 252f, 48f, if (reps > targetReps) orange else green, true)
                text(canvas, "목표", 284f, 209f, 12f, muted)
                text(canvas, "${targetReps}회", 284f, 248f, 18f, white)
                divider(canvas, 279f)
                bottom(canvas, 309f, 354f, 22f, showBpm = false)
            }
            1 -> {
                text(canvas, reps.toString(), 175f, 252f, 48f, if (reps > targetReps) orange else green, true)
                text(canvas, "/ ${targetReps}회", 284f, 249f, 14f, muted)
                divider(canvas, 279f)
                text(canvas, "10", 130f, 337f, 22f, white, true)
                text(canvas, "kg", 184f, 335f, 12f, muted)
                text(canvas, "♥", 249f, 337f, 16f, red)
                text(canvas, "112", 306f, 337f, 22f, red, true)
            }
            2 -> {
                text(canvas, "8", 171f, 242f, 44f, green, true)
                text(canvas, "/ 20회", 286f, 240f, 16f, muted)
                divider(canvas, 273f)
                bottom(canvas, 303f, 355f, 25f)
            }
            else -> {
                text(canvas, "8", 175f, 241f, 48f, green, true)
                text(canvas, "/ 20회", 284f, 238f, 14f, muted)
                text(canvas, "1세트", 216f, 276f, 12f, muted)
                divider(canvas, 294f)
                bottom(canvas, 323f, 366f, 21f)
            }
        }
        paint.color = muted; paint.style = Paint.Style.STROKE; paint.strokeWidth = 2.5f
        paint.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(210f, 405f, 216f, 410f, paint)
        canvas.drawLine(216f, 410f, 222f, 405f, paint)
        paint.style = Paint.Style.FILL
    }
}
