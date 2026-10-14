package dev.kyungmin.kcoach.phone.ui

import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

@Composable
private fun DetailHeader(title: String, back: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(58.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(54.dp, 58.dp).pressable(back), contentAlignment = Alignment.Center) {
            KIcon(KIcon.Back, KColors.White, size = 28.dp)
        }
        Text(title, color = KColors.White, fontSize = 25.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Box(Modifier.size(54.dp, 58.dp), contentAlignment = Alignment.Center) {
            KIcon(KIcon.Dots, KColors.Muted, size = 24.dp)
        }
    }
}

@Composable
private fun WideButton(text: String, background: Color, content: Color, height: Int, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(height.dp)
            .alpha(if (enabled) 1f else 0.6f)
            .clip(KShape.Control)
            .background(background)
            .then(if (enabled) Modifier.pressable(onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = content, fontSize = 18.sp, fontWeight = FontWeight.Medium)
    }
}

// ---- Calibration -----------------------------------------------------------

class CalibrationRowUi(val id: String, val name: String, val muscles: String, val status: String, val selected: Boolean, val thumbnail: () -> View)

class CalibrationProfileUi(val title: String, val values: List<Pair<String, String>>, val note: String)

class CalibrationUi(
    val selectedName: String,
    val status: String,
    val statusAmber: Boolean,
    val instructions: String,
    val manualRequired: Boolean,
    val profile: CalibrationProfileUi?,
    val rows: List<CalibrationRowUi>,
)

/** Live state of the main button, driven by watch events. */
class CalibrationButtonUi(val text: String, val enabled: Boolean, val textAmber: Boolean = false)

class CalibrationActions(
    val toggle: () -> Unit,
    val deleteProfile: () -> Unit,
    val select: (String) -> Unit,
    val openSensorView: (() -> Unit)? = null,
    val openCaptureReview: (() -> Unit)? = null,
)

@Composable
fun CalibrationScreen(
    ui: CalibrationUi,
    sessionActive: () -> Boolean,
    button: () -> CalibrationButtonUi,
    actions: CalibrationActions,
) {
    val state = button()
    val active = sessionActive()
    Column(Modifier.fillMaxSize().background(KColors.Bg).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 20.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("측정·보정", color = KColors.White, fontSize = 32.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(24.dp))
        Text(ui.selectedName, color = KColors.White, fontSize = 24.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(7.dp))
        Text(ui.status, color = if (ui.statusAmber) KColors.Accent else KColors.Soft, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(12.dp))
        Text(ui.instructions, color = KColors.Muted, fontSize = 15.sp)
        Spacer(Modifier.height(20.dp))
        WideButton(
            state.text,
            if (active) KColors.Accent else KColors.SurfaceRaised,
            if (state.textAmber) KColors.Accent else if (active) KColors.OnAccent else KColors.Muted,
            58,
            enabled = state.enabled && !ui.manualRequired,
            onClick = actions.toggle,
        )
        actions.openSensorView?.let { open ->
            Spacer(Modifier.height(10.dp))
            WideButton("센서·3D 보기", KColors.SurfaceRaised, KColors.Soft, 48,
                enabled = !active, onClick = open)
        }
        actions.openCaptureReview?.let { open ->
            Spacer(Modifier.height(10.dp))
            WideButton("저장한 촬영·반복 구간 확인", KColors.SurfaceRaised, KColors.Soft, 48,
                enabled = !active, onClick = open)
        }
        ui.profile?.let { profile ->
            Spacer(Modifier.height(28.dp))
            SectionCard(profile.title, profile.values)
            Spacer(Modifier.height(8.dp))
            Text(profile.note, color = KColors.Muted, fontSize = 12.sp)
            Spacer(Modifier.height(12.dp))
            WideButton("보정된 기록 삭제", Color(0xFF2A1613), KColors.Red, 54, onClick = actions.deleteProfile)
        }
        Spacer(Modifier.height(28.dp))
        Text("보정할 운동 선택", color = KColors.Muted, fontSize = 13.sp)
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(KColors.Hairline))
        ui.rows.forEach { row ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .alpha(if (active) 0.55f else 1f)
                    .clip(KShape.Control)
                    .background(if (row.selected) KColors.SurfaceRaised else Color.Transparent)
                    .pressable { if (!active) actions.select(row.id) }
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AndroidView(factory = { row.thumbnail() }, modifier = Modifier.size(58.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(row.name, color = KColors.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(2.dp))
                    Text("${row.muscles} · ${row.status}", color = KColors.Muted, fontSize = 12.sp)
                }
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(KColors.Hairline))
        }
    }
}

// ---- My mountain -----------------------------------------------------------

@Composable
fun MyMountainScreen(back: () -> Unit, start: () -> Unit, finish: () -> Unit) {
    Column(Modifier.fillMaxSize().background(KColors.Bg).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp)) {
        DetailHeader("마이마운틴", back)
        Spacer(Modifier.height(28.dp))
        Text("오늘 설정", color = KColors.White, fontSize = 28.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        Text("경사, 속도, 시간은 매번 바꿀 수 있어요.", color = KColors.Muted, fontSize = 15.sp)
        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth()) {
            NumberCard("경사", "13", Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            NumberCard("속도", "4.0", Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            NumberCard("시간", "30", Modifier.weight(1f))
        }
        Spacer(Modifier.height(28.dp))
        WideButton("시작", KColors.Accent, KColors.OnAccent, 64, onClick = start)
        Spacer(Modifier.height(12.dp))
        WideButton("건너뛰기", KColors.SurfaceRaised, KColors.White, 56, onClick = finish)
    }
}

@Composable
private fun NumberCard(title: String, initial: String, modifier: Modifier) {
    var value by remember { mutableStateOf(initial) }
    Column(modifier.heightIn(min = 118.dp).clip(KShape.Control).background(KColors.Surface).padding(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 10.dp)) {
        Text(title, color = KColors.Muted, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(12.dp))
        BasicTextField(
            value = value,
            onValueChange = { value = it },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            textStyle = TextStyle(color = KColors.White, fontSize = 28.sp, fontWeight = FontWeight.Medium),
            cursorBrush = SolidColor(KColors.Accent),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ---- Workout result --------------------------------------------------------

class ResultSectionUi(val title: String, val values: List<Pair<String, String>>)

class ResultUi(
    val title: String,
    val subtitle: String,
    val sections: List<ResultSectionUi>,
    val footnote: String,
    val records: List<Pair<String, String>> = emptyList(),
)

@Composable
fun WorkoutResultScreen(result: ResultUi, back: () -> Unit, toRecords: () -> Unit) {
    Column(Modifier.fillMaxSize().background(KColors.Bg).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 20.dp)) {
        DetailHeader("운동 완료 결과", back)
        Spacer(Modifier.height(24.dp))
        Text(result.title, color = KColors.White, fontSize = 32.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        Text(result.subtitle, color = KColors.Muted, fontSize = 14.sp)
        if (result.records.isNotEmpty()) {
            Spacer(Modifier.height(28.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text("${result.records.size}", color = KColors.Accent, fontSize = 56.sp, fontWeight = FontWeight.Medium, letterSpacing = (-1).sp)
                Spacer(Modifier.width(10.dp))
                Text("개 갱신", color = KColors.White, fontSize = 18.sp, modifier = Modifier.padding(bottom = 10.dp))
            }
            Spacer(Modifier.height(6.dp))
            SectionCard("오늘 깬 기록", result.records)
        }
        Spacer(Modifier.height(28.dp))
        Text("세트별 센서 추정", color = KColors.Soft, fontSize = 15.sp)
        Spacer(Modifier.height(10.dp))
        result.sections.forEach {
            SectionCard(it.title, it.values)
            Spacer(Modifier.height(24.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(result.footnote, color = KColors.Muted, fontSize = 13.sp)
        Spacer(Modifier.height(18.dp))
        WideButton("운동 기록으로 돌아가기", KColors.SurfaceRaised, KColors.White, 58, onClick = toRecords)
    }
}
