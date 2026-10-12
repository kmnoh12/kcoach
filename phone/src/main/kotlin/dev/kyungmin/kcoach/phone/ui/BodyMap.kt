package dev.kyungmin.kcoach.phone.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * One muscle region drawn as a smooth closed curve through [points] (left half of the figure, 100 x 200 grid);
 * it is mirrored to the right side. [part] is the body part it lights up with, null for regions that stay neutral.
 */
private class Region(val part: String?, vararg val points: Pair<Float, Float>)

private fun r(part: String?, vararg p: Pair<Float, Float>) = Region(part, *p)

private val upperArm = arrayOf(20f to 54f, 17f to 62f, 17f to 71f, 22f to 75f, 26f to 69f, 27f to 58f)
private val forearm = arrayOf(17f to 77f, 15f to 87f, 14f to 99f, 18f to 107f, 22f to 99f, 23f to 87f, 22f to 77f)
private val hand = arrayOf(14f to 110f, 13f to 116f, 17f to 120f, 21f to 116f, 20f to 110f)
private val delt = arrayOf(30f to 34f, 23f to 36f, 19f to 43f, 19f to 52f, 24f to 56f, 29f to 50f, 31f to 42f)
private val foot = arrayOf(36f to 186f, 36f to 192f, 43f to 194f, 47f to 190f, 46f to 185f)

private val Front = listOf(
    r(null, 46f to 22f, 45f to 29f, 40f to 33f, 44f to 31f, 50f to 31f, 50f to 22f),
    r("어깨", *delt),
    r("가슴", 49f to 38f, 41f to 35f, 33f to 38f, 31f to 45f, 34f to 52f, 42f to 56f, 49f to 53f),
    r(null, 42f to 58f, 49f to 58f, 49f to 66f, 42f to 66f),
    r(null, 42f to 68f, 49f to 68f, 49f to 76f, 42f to 76f),
    r(null, 43f to 78f, 49f to 78f, 49f to 88f, 44f to 88f),
    r(null, 34f to 57f, 40f to 60f, 41f to 74f, 40f to 88f, 35f to 84f, 32f to 70f),
    r("이두", *upperArm),
    r(null, *forearm),
    r(null, *hand),
    r("하체", 49f to 92f, 38f to 91f, 33f to 102f, 32f to 118f, 36f to 134f, 43f to 138f, 48f to 127f, 50f to 108f),
    r("하체", 36f to 143f, 35f to 154f, 36f to 168f, 40f to 181f, 45f to 181f, 47f to 168f, 47f to 152f, 44f to 143f),
    r(null, *foot),
)

private val Back = listOf(
    r(null, 46f to 22f, 45f to 29f, 50f to 31f, 50f to 22f),
    r("등", 50f to 27f, 44f to 28f, 36f to 33f, 32f to 37f, 40f to 45f, 50f to 58f),
    r("어깨", *delt),
    r("등", 42f to 47f, 33f to 44f, 32f to 56f, 36f to 70f, 44f to 79f, 49f to 82f, 49f to 60f),
    r("삼두", *upperArm),
    r(null, *forearm),
    r(null, *hand),
    r("하체", 49f to 88f, 40f to 86f, 34f to 92f, 33f to 102f, 40f to 108f, 49f to 106f),
    r("하체", 49f to 110f, 38f to 110f, 33f to 118f, 35f to 133f, 43f to 138f, 48f to 128f, 50f to 114f),
    r("하체", 36f to 143f, 35f to 152f, 37f to 166f, 41f to 174f, 46f to 166f, 47f to 150f, 44f to 143f),
    r(null, *foot),
)

/** Front and back figure; each worked part fills with the accent color, denser the more it was worked (0..1). */
@Composable
fun BodyMap(intensity: Map<String, Double>, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf("앞" to Front, "뒤" to Back).forEach { (label, regions) ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Canvas(Modifier.fillMaxWidth().aspectRatio(0.5f)) { drawFigure(regions, intensity) }
                Text(label, color = KColors.Muted, fontSize = 11.sp, textAlign = TextAlign.Center)
            }
        }
    }
}

private fun DrawScope.drawFigure(regions: List<Region>, intensity: Map<String, Double>) {
    val s = size.width / 100f
    val line = Stroke(width = 0.8f * s.coerceAtLeast(1f))
    // Head sits above both views.
    val head = Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset(41.5f * s, 3f * s), Size(17f * s, 21f * s))) }
    drawPath(head, KColors.Surface)
    drawPath(head, KColors.Hairline, style = line)
    regions.forEach { region ->
        val level = region.part?.let { intensity[it] } ?: 0.0
        val fill = if (level > 0.0) lerp(KColors.SurfaceRaised, KColors.Accent, (0.2 + 0.8 * level).toFloat()) else KColors.Surface
        listOf(false, true).forEach { mirrored ->
            val path = smoothClosed(region.points.map { (x, y) -> Offset((if (mirrored) 100f - x else x) * s, y * s) })
            drawPath(path, fill)
            drawPath(path, KColors.Hairline, style = line)
        }
    }
}

/** Catmull-Rom spline through [pts] turned into cubic segments, so a few points give an organic outline. */
private fun smoothClosed(pts: List<Offset>): Path {
    val path = Path()
    val n = pts.size
    path.moveTo(pts[0].x, pts[0].y)
    for (i in 0 until n) {
        val p0 = pts[(i - 1 + n) % n]
        val p1 = pts[i]
        val p2 = pts[(i + 1) % n]
        val p3 = pts[(i + 2) % n]
        val c1 = Offset(p1.x + (p2.x - p0.x) / 6f, p1.y + (p2.y - p0.y) / 6f)
        val c2 = Offset(p2.x - (p3.x - p1.x) / 6f, p2.y - (p3.y - p1.y) / 6f)
        path.cubicTo(c1.x, c1.y, c2.x, c2.y, p2.x, p2.y)
    }
    path.close()
    return path
}
