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

/** Proposed start/end screens, not bound to production commands or fabricated workout totals. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w216dp-h216dp-round-xhdpi")
class WatchLifecycleSnapshotTest {
    @Test fun renderD2StartAndWorkoutComplete() {
        val output = File("build/outputs/watch-design").apply { mkdirs() }
        fun save(bitmap: Bitmap, name: String) = File(output, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        val images = listOf(false, true).map { finished ->
            val view = LifecyclePreview(RuntimeEnvironment.getApplication(), finished)
            val exact = View.MeasureSpec.makeMeasureSpec(432, View.MeasureSpec.EXACTLY)
            view.measure(exact, exact); view.layout(0, 0, 432, 432)
            Bitmap.createBitmap(432, 432, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        save(images[0], "draft-d2-before-workout")
        save(images[1], "draft-d2-workout-complete")
        val board = Bitmap.createBitmap(1000, 650, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(board)
        canvas.drawColor(Color.rgb(24, 26, 29))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 32f; typeface = Typeface.DEFAULT_BOLD }
        canvas.drawText("D2 · 운동 전과 전체 운동 완료", 40f, 48f, paint)
        paint.textSize = 22f; paint.color = Color.rgb(183, 190, 199); paint.typeface = Typeface.DEFAULT
        canvas.drawText("폰과 같은 버튼 곡률 6dp · 상태마다 필요한 내용만", 40f, 82f, paint)
        images.forEachIndexed { index, bitmap ->
            val x = if (index == 0) 52f else 516f
            paint.textSize = 25f; paint.color = Color.WHITE; paint.typeface = Typeface.DEFAULT_BOLD
            canvas.drawText(if (index == 0) "운동 전 · 준비된 루틴이 있을 때" else "마지막 세트 후 · 전체 운동 종료", x + 12f, 132f, paint)
            canvas.save()
            canvas.clipPath(Path().apply { addCircle(x + 216f, 370f, 214f, Path.Direction.CW) })
            canvas.drawBitmap(bitmap, x, 154f, null)
            canvas.restore()
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 1f; paint.color = Color.rgb(73, 78, 87)
            canvas.drawCircle(x + 216f, 370f, 214f, paint)
            paint.style = Paint.Style.FILL
        }
        paint.textSize = 21f; paint.color = Color.rgb(183, 190, 199); paint.typeface = Typeface.DEFAULT
        canvas.drawText("구성 제안 · 루틴/운동명은 예시 · 전체 종료는 폰 확인 후 · 앱 미적용", 40f, 626f, paint)
        save(board, "comparison-d2-start-complete")
        board.recycle(); images.forEach { it.recycle() }
    }
}

private class LifecyclePreview(context: Context, private val finished: Boolean) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val green = Color.rgb(0, 230, 140)
    private val white = Color.rgb(245, 247, 250)
    private val muted = Color.rgb(164, 177, 190)
    private fun text(canvas: Canvas, value: String, y: Float, sp: Float, color: Int, bold: Boolean = false) {
        paint.color = color; paint.textSize = sp * resources.displayMetrics.scaledDensity
        paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        paint.textAlign = Paint.Align.CENTER; paint.style = Paint.Style.FILL
        canvas.drawText(value, 216f, y, paint)
    }
    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        text(canvas, "10:09", 38f, 12f, white)
        if (finished) {
            paint.color = green; paint.style = Paint.Style.STROKE; paint.strokeWidth = 5f
            paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND
            canvas.drawCircle(216f, 107f, 30f, paint)
            canvas.drawPath(Path().apply { moveTo(201f, 107f); lineTo(212f, 118f); lineTo(233f, 96f) }, paint)
            paint.style = Paint.Style.FILL
            text(canvas, "운동 완료", 183f, 24f, white, true)
            text(canvas, "수고했어요", 224f, 14f, muted)
            text(canvas, "폰에서 기록을 확인하세요", 259f, 12f, muted)
        } else {
            text(canvas, "운동 준비", 95f, 14f, muted)
            text(canvas, "상체 루틴", 159f, 24f, white, true)
            text(canvas, "첫 운동", 201f, 12f, muted)
            text(canvas, "덤벨 컬", 241f, 16f, white, true)
        }
        paint.color = green; paint.style = Paint.Style.FILL
        val radius = 6f * resources.displayMetrics.density
        canvas.drawRoundRect(RectF(76f, 280f, 356f, 376f), radius, radius, paint)
        text(canvas, if (finished) "확인" else "운동 시작", 339f, 16f, Color.rgb(6, 26, 19), true)
    }
}
