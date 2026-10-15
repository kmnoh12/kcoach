package dev.kyungmin.kcoach.wear

import android.content.Context
import android.content.Intent
import android.graphics.*
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.RobolectricTestRunner
import java.io.File

/** Design-only capture harness; not part of the app or its production test suite. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w216dp-h216dp-round-xhdpi")
class WatchDesignSnapshotTest {
    private val output = File("build/outputs/watch-design")
    private fun save(bitmap: Bitmap, name: String) {
        output.mkdirs()
        File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun render(view: View): Bitmap {
        view.measure(View.MeasureSpec.makeMeasureSpec(432, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(432, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 432, 432)
        return Bitmap.createBitmap(432, 432, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
    }
    @Test fun captureCurrentPublishedViewAndThreeDesigns() {
        val activity = Robolectric.buildActivity(PublishedWatchPreviewActivity::class.java).setup().get()
        val app = RuntimeEnvironment.getApplication()
        app.sendBroadcast(Intent(WearWorkoutService.ACTION_STATE).setPackage(app.packageName)
            .putExtra("session", "design-preview").putExtra("setId", "set").putExtra("attemptId", "attempt")
            .putExtra("exerciseId", "curl").putExtra("exerciseName", "덤벨 컬")
            .putExtra("paired", true).putExtra("running", true).putExtra("resting", false)
            .putExtra("setIndex", 0).putExtra("weightKg", 10.0).putExtra("targetReps", 20)
            .putExtra("reps", 8).putExtra("heartRate", 112).putExtra("phase", "WORKING")
            .putExtra("tempoEnabled", true).putExtra("tempoHint", "1회 3.2초 · 목표보다 느림"))
        shadowOf(Looper.getMainLooper()).idle()
        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val current = render(root)
        save(current, "current-published")
        val drafts = (0..2).map { render(DesignView(activity, it)) }
        drafts.forEachIndexed { index, bitmap -> save(bitmap, "draft-${'a' + index}") }

        val board = Bitmap.createBitmap(1000, 1180, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(board)
        canvas.drawColor(Color.rgb(24, 26, 29))
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 32f; typeface = Typeface.DEFAULT_BOLD }
        canvas.drawText("워치 운동 화면 · 현재 + 시안 3개", 40f, 48f, p)
        p.textSize = 22f; p.color = Color.rgb(183, 190, 199); p.typeface = Typeface.DEFAULT
        canvas.drawText("덤벨 컬 · 1세트 · 10kg · 8 / 목표 20회 · 심박 112", 40f, 82f, p)
        val names = listOf("현재 · 배포 코드 0.2.7", "A · 횟수 강조", "B · 중량과 횟수", "C · 목표 진행")
        (listOf(current) + drafts).forEachIndexed { index, bitmap ->
            val x = if (index % 2 == 0) 52f else 516f
            val y = if (index < 2) 154f else 660f
            p.textSize = 25f; p.color = Color.WHITE; p.typeface = Typeface.DEFAULT_BOLD
            canvas.drawText(names[index], x + 12f, y - 22f, p)
            canvas.save()
            canvas.clipPath(Path().apply { addCircle(x + 216f, y + 216f, 214f, Path.Direction.CW) })
            canvas.drawBitmap(bitmap, x, y, null)
            canvas.restore()
            p.style = Paint.Style.STROKE; p.strokeWidth = 1f; p.color = Color.rgb(73, 78, 87)
            canvas.drawCircle(x + 216f, y + 216f, 214f, p)
            p.style = Paint.Style.FILL
        }
        p.color = Color.rgb(183, 190, 199); p.typeface = Typeface.DEFAULT; p.textSize = 21f
        canvas.drawText("현재 = 앱뷰 렌더링 · 값은 비교용 예시 · 시안은 앱 미적용", 40f, 1144f, p)
        save(board, "comparison")
        board.recycle(); current.recycle(); drafts.forEach { it.recycle() }
        activity.finish()
    }
}

private class DesignView(context: Context, private val variant: Int) : View(context) {
    private val white = Color.rgb(245, 247, 250)
    private val muted = Color.rgb(164, 177, 190)
    private val green = Color.rgb(0, 230, 140)
    private val red = Color.rgb(255, 105, 105)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private fun text(canvas: Canvas, value: String, x: Float, baseline: Float, sp: Float,
        color: Int = white, bold: Boolean = false) {
        paint.style = Paint.Style.FILL; paint.color = color
        paint.textAlign = Paint.Align.CENTER; paint.textSize = sp * resources.displayMetrics.scaledDensity
        paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        paint.fontFeatureSettings = "tnum"
        canvas.drawText(value, x, baseline, paint)
    }
    private fun more(canvas: Canvas, baseline: Float) {
        text(canvas, "더 보기", 216f, baseline, 11f, muted)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 2.5f; paint.strokeCap = Paint.Cap.ROUND
        paint.color = muted
        canvas.drawLine(210f, baseline + 10f, 216f, baseline + 15f, paint)
        canvas.drawLine(216f, baseline + 15f, 222f, baseline + 10f, paint)
        paint.style = Paint.Style.FILL
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)
        when (variant) {
            0 -> {
                text(canvas, "10:09", 216f, 42f, 12f, muted)
                text(canvas, "덤벨 컬", 216f, 94f, 16f, bold = true)
                text(canvas, "1세트 · 10kg", 216f, 129f, 12f, muted)
                text(canvas, "자동 횟수", 216f, 174f, 12f, muted)
                text(canvas, "8", 216f, 259f, 40f, green, true)
                text(canvas, "목표 20회", 216f, 295f, 12f, muted)
                text(canvas, "♥ 112 bpm", 216f, 338f, 12f, red)
                more(canvas, 383f)
            }
            1 -> {
                text(canvas, "10:09", 216f, 42f, 12f, muted)
                text(canvas, "덤벨 컬", 216f, 94f, 16f, bold = true)
                text(canvas, "1세트", 216f, 131f, 12f, muted)
                text(canvas, "중량", 132f, 177f, 12f, muted)
                text(canvas, "자동 횟수", 296f, 177f, 12f, muted)
                text(canvas, "10", 132f, 252f, 28f, white, true)
                text(canvas, "8", 296f, 252f, 40f, green, true)
                text(canvas, "kg", 132f, 281f, 12f, muted)
                text(canvas, "목표 20회", 296f, 281f, 12f, muted)
                paint.color = Color.rgb(54, 61, 68); paint.strokeWidth = 1f
                canvas.drawLine(216f, 158f, 216f, 291f, paint)
                text(canvas, "♥ 112 bpm", 216f, 330f, 12f, red)
                more(canvas, 383f)
            }
            else -> {
                text(canvas, "10:09", 216f, 37f, 12f, muted)
                text(canvas, "덤벨 컬", 216f, 83f, 16f, bold = true)
                text(canvas, "1세트 · 10kg", 216f, 119f, 12f, muted)
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 7f
                paint.strokeCap = Paint.Cap.ROUND; paint.color = Color.rgb(40, 47, 53)
                val ring = RectF(108f, 137f, 324f, 353f)
                canvas.drawArc(ring, -90f, 360f, false, paint)
                paint.color = green
                canvas.drawArc(ring, -90f, 360f * 8f / 20f, false, paint)
                paint.style = Paint.Style.FILL
                text(canvas, "자동 횟수", 216f, 210f, 12f, muted)
                text(canvas, "8", 216f, 284f, 40f, green, true)
                text(canvas, "목표 20회", 216f, 318f, 12f, muted)
                text(canvas, "♥ 112 bpm", 216f, 385f, 12f, red)
                // Keep the affordance compact instead of shrinking essential information.
                paint.color = muted; paint.style = Paint.Style.STROKE; paint.strokeWidth = 2.5f
                canvas.drawLine(210f, 407f, 216f, 412f, paint)
                canvas.drawLine(216f, 412f, 222f, 407f, paint)
                paint.style = Paint.Style.FILL
            }
        }
    }
}
