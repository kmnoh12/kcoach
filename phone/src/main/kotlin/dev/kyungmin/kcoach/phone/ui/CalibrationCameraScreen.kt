package dev.kyungmin.kcoach.phone.ui

import android.os.SystemClock
import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.kyungmin.kcoach.core.CameraPoseFamily
import dev.kyungmin.kcoach.core.CameraPoseGuide
import dev.kyungmin.kcoach.core.GuideAnchor
import dev.kyungmin.kcoach.core.GuidePoint
import kotlinx.coroutines.delay

/** Everything the capture screen shows; the activity owns the camera and capture logic. */
data class CalibrationCameraUi(
    val status: String = "카메라 준비 중…",
    val ready: Boolean = false,
    val recording: Boolean = false,
    val stopEnabled: Boolean = false,
    val saving: Boolean = false,
    val saveRetry: Boolean = false,
    /** A refused start (watch, storage) keeps its message until the pose is broken. */
    val blocked: Boolean = false,
    val permissionNeeded: Boolean = false,
    val failureReport: String? = null,
    val headline: String = "",
    val startHint: String? = null,
    val wearingSummary: String? = null,
    val wearingFromSettings: Boolean = true,
    /** Pattern candidate test whose own wearing value is unusable: only leaving and re-picking the candidate fixes it. */
    val candidateWearingInvalid: Boolean = false,
    val recordingSinceMs: Long? = null,
)

private const val SHEET_MIN_DP = 200

/**
 * The 3:4 selfie frame is shown whole (no crop), so what the user sees is what pose detection sees.
 * Tall phones put the controls in a sheet below it; screens without room for that sheet
 * (e.g. a 4:3-ish foldable) lay a compact panel over the bottom of the frame instead.
 */
@Composable
fun CalibrationCameraScreen(
    ui: CalibrationCameraUi, exerciseName: String, family: CameraPoseFamily?, preview: View, overlay: View,
    back: () -> Unit, openSaved: () -> Unit, openWearingSettings: () -> Unit, stop: () -> Unit,
    retrySave: () -> Unit, requestPermission: () -> Unit, copyError: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(KColors.Bg).safeDrawingPadding()) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val stacked = maxHeight - maxWidth * 4f / 3f >= SHEET_MIN_DP.dp
            // Largest whole 3:4 frame that fits; never wider than the screen.
            val frameWidth = minOf(maxWidth, maxHeight * 3f / 4f)
            val fullWidth = maxWidth
            val frame = @Composable { modifier: Modifier ->
                Box(modifier.background(Color.Black)) {
                    AndroidView({ preview }, Modifier.fillMaxSize())
                    AndroidView({ overlay }, Modifier.fillMaxSize())
                    TopBar(ui, exerciseName, back, openSaved)
                }
            }
            if (stacked) {
                Column(Modifier.fillMaxSize()) {
                    frame(Modifier.size(fullWidth, fullWidth * 4f / 3f))
                    Controls(ui, family, back, openWearingSettings, stop, retrySave, requestPermission, copyError,
                        Modifier.fillMaxWidth().weight(1f).offset(y = (-8).dp).clip(KShape.Sheet).background(KColors.Surface))
                }
            } else {
                frame(Modifier.align(Alignment.TopCenter).size(frameWidth, frameWidth * 4f / 3f))
                Controls(ui, family, back, openWearingSettings, stop, retrySave, requestPermission, copyError,
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp)
                        .clip(KShape.Panel).background(KColors.Surface.copy(alpha = 0.92f)))
            }
        }
    }
}

@Composable
private fun TopBar(ui: CalibrationCameraUi, exerciseName: String, back: () -> Unit, openSaved: () -> Unit) {
    Box(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.35f)).padding(horizontal = 8.dp, vertical = 6.dp)) {
        if (ui.recordingSinceMs != null) {
            var now by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
            LaunchedEffect(ui.recordingSinceMs) { while (true) { now = SystemClock.uptimeMillis(); delay(500) } }
            val seconds = ((now - ui.recordingSinceMs) / 1000).coerceAtLeast(0)
            Text("● %02d:%02d".format(seconds / 60, seconds % 60), color = KColors.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Center).clip(KShape.Control).background(KColors.Red).padding(horizontal = 14.dp, vertical = 5.dp))
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(KShape.Control).clickable(onClick = back), contentAlignment = Alignment.Center) {
                    KIcon(KIcon.Back, KColors.White)
                }
                Text(exerciseName, color = KColors.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("저장한 촬영", color = KColors.White, fontSize = 13.sp,
                    modifier = Modifier.clip(KShape.Control).clickable(onClick = openSaved).padding(horizontal = 10.dp, vertical = 8.dp))
            }
        }
    }
}

@Composable
private fun Controls(
    ui: CalibrationCameraUi, family: CameraPoseFamily?, back: () -> Unit, openWearingSettings: () -> Unit, stop: () -> Unit,
    retrySave: () -> Unit, requestPermission: () -> Unit, copyError: () -> Unit, modifier: Modifier,
) {
    Column(modifier.padding(horizontal = 20.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(ui.status, color = if (ui.ready || ui.recording) KColors.Accent else KColors.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        if (!ui.recording) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (family != null) {
                    MiniPictogram(family, Modifier.size(54.dp).clip(KShape.Control).background(KColors.Bg))
                    Spacer(Modifier.width(12.dp))
                }
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(ui.headline, color = KColors.White, fontSize = 14.sp)
                    ui.startHint?.let { Text(it, color = KColors.Muted, fontSize = 13.sp) }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(KColors.Hairline))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(ui.wearingSummary?.let { "워치 $it" } ?: if (ui.candidateWearingInvalid) "후보의 워치 착용 정보가 올바르지 않아요" else "워치 착용 정보가 없어요", color = KColors.Muted, fontSize = 13.sp,
                    modifier = Modifier.weight(1f))
                if (ui.wearingFromSettings) Text(if (ui.wearingSummary == null) "설정에서 저장  ›" else "설정에서 변경  ›",
                    color = KColors.Accent, fontSize = 13.sp,
                    modifier = Modifier.clip(KShape.Control).clickable(onClick = openWearingSettings).padding(6.dp))
            }
        }
        if (ui.candidateWearingInvalid && !ui.recording) ActionButton("뒤로 가서 후보 시험 다시 선택", KColors.Accent, KColors.OnAccent, back)
        ui.failureReport?.let { ActionButton("오류 정보 복사", KColors.SurfaceRaised, KColors.White, copyError) }
        if (ui.permissionNeeded) ActionButton("카메라 권한 허용", KColors.Accent, KColors.OnAccent, requestPermission)
        if (ui.saveRetry) ActionButton("저장 다시 시도", KColors.Accent, KColors.OnAccent, retrySave)
        if (ui.recording && !ui.saveRetry) ActionButton("촬영 종료 · 저장", KColors.Red, KColors.White, stop, enabled = ui.stopEnabled)
    }
}

@Composable
private fun ActionButton(label: String, color: Color, content: Color, onClick: () -> Unit, enabled: Boolean = true) {
    Box(Modifier.fillMaxWidth().height(50.dp).clip(KShape.Control).background(if (enabled) color else KColors.SurfaceRaised)
        .clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, color = if (enabled) content else KColors.Muted, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

/** The same start-pose figure the overlay draws, at thumbnail size and facing right. */
@Composable
private fun MiniPictogram(family: CameraPoseFamily, modifier: Modifier) {
    val figure = remember(family) {
        val anchor = if (family.front) GuideAnchor(GuidePoint(.13, 0.0), GuidePoint(-.13, 0.0), 1.0, 1)
        else GuideAnchor(GuidePoint(0.0, 0.0), GuidePoint(0.0, 0.0), 1.0, 1)
        CameraPoseGuide.build(family, anchor)
    }
    Canvas(modifier.padding(6.dp)) {
        val points = figure.limbs.flatMap { it.points } + figure.torso + listOf(
            GuidePoint(figure.head.x, figure.head.y - figure.headRadius), GuidePoint(figure.head.x, figure.head.y + figure.headRadius))
        val minX = points.minOf { it.x } - .06; val maxX = points.maxOf { it.x } + .06
        val minY = points.minOf { it.y } - .03; val maxY = points.maxOf { it.y } + .03
        val k = minOf(size.width / (maxX - minX), size.height / (maxY - minY)).toFloat()
        val ox = (size.width - (maxX - minX).toFloat() * k) / 2; val oy = (size.height - (maxY - minY).toFloat() * k) / 2
        fun o(p: GuidePoint) = Offset(ox + (p.x - minX).toFloat() * k, oy + (p.y - minY).toFloat() * k)
        val grey = Color(0xFFCDD1CF)
        if (figure.torso.size == 2) drawLine(grey, o(figure.torso[0]), o(figure.torso[1]), figure.torsoWidth.toFloat() * k, StrokeCap.Round)
        else figure.torso.zipWithNext().plus(figure.torso.last() to figure.torso.first()).forEach { (a, b) -> drawLine(grey, o(a), o(b), .08f * k, StrokeCap.Round) }
        drawCircle(grey, figure.headRadius.toFloat() * k, o(figure.head))
        figure.limbs.sortedBy { it.primary }.forEach { limb ->
            val color = if (limb.primary) Color(0xFFF2F4F3) else grey.copy(alpha = .5f)
            limb.points.zipWithNext().forEach { (a, b) -> drawLine(color, o(a), o(b), limb.width.toFloat() * k, StrokeCap.Round) }
        }
    }
}
