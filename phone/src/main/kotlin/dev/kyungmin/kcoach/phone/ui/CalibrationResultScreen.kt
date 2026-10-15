package dev.kyungmin.kcoach.phone.ui

import android.net.Uri
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay

/** Times share the reference video's presentation clock. Markers remain proposals, never labels. */
data class CapturePlaybackUi(
    val id: String, val name: String, val videoPath: String?, val endMs: Int,
    /** (video ms, signal value) pairs of the graph; what the value means is [graphTitle]. */
    val heights: List<Pair<Int, Float>>, val intervals: List<Pair<Int, Int>>,
    val confirmedCount: Int? = null, val watchCount: Int? = null, val message: String = "", val diagnostics: String = "",
    val graphTitle: String = "영상 속 손목 높이",
)

/** One saved capture in the list: [meta] is the date and the confirmation state. */
data class CaptureRowUi(val id: String, val name: String, val meta: String)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CalibrationResultScreen(
    clip: CapturePlaybackUi?, clips: List<CaptureRowUi>, busy: Boolean,
    select: (String) -> Unit, answer: (Int) -> Unit, back: () -> Unit, research: () -> Unit,
    loading: Boolean = false, error: String? = null, retry: () -> Unit = {},
    deleteClips: (Set<String>) -> Unit = {}, deleteClip: () -> Unit = {}, notice: String? = null,
) {
    var researchVisible by remember { mutableStateOf(false) }
    var other by remember(clip?.id) { mutableStateOf(false) }
    var number by remember(clip?.id) { mutableStateOf("") }
    var playbackMs by remember(clip?.id) { mutableIntStateOf(0) }
    var selecting by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf(emptySet<String>()) }
    var confirmDelete by remember { mutableStateOf(false) }
    // Rows that disappear (deleted elsewhere) must not stay selected.
    LaunchedEffect(clips) { picked = picked.intersect(clips.map { it.id }.toSet()) }
    BackHandler(selecting) { selecting = false; picked = emptySet() }

    Column(Modifier.fillMaxSize().background(KColors.Bg).safeDrawingPadding()) {
        when {
            clip != null -> TopBar(clip.name, back, onTitleLongPress = { researchVisible = !researchVisible }) {
                Box(Modifier.size(48.dp).pressable { confirmDelete = true }.semantics { contentDescription = "삭제" }, contentAlignment = Alignment.Center) {
                    KIcon(KIcon.Trash, if (busy) KColors.Faint else KColors.Soft, size = 24.dp)
                }
            }
            selecting -> SelectionBar(picked.size, allSelected = clips.isNotEmpty() && picked.size == clips.size,
                toggleAll = { picked = if (picked.size == clips.size) emptySet() else clips.map { it.id }.toSet() },
                cancel = { selecting = false; picked = emptySet() })
            else -> TopBar("저장한 촬영", back, onTitleLongPress = { researchVisible = !researchVisible })
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = if (selecting) 96.dp else 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (notice != null) Text(notice, color = KColors.Accent, fontSize = 14.sp)
                if (loading) Text("촬영 자료를 불러오는 중…", color = KColors.Soft)
                if (error != null) {
                    Text(error, color = KColors.Red)
                    ResultAction("다시 불러오기", enabled = !loading && !busy, action = retry)
                }
                if (clip == null) {
                    if (clips.isEmpty() && !loading && error == null) Text("아직 촬영 자료가 없어요.", color = KColors.Soft)
                    clips.forEachIndexed { index, row ->
                        if (index > 0) Box(Modifier.fillMaxWidth().height(.5.dp).background(KColors.Hairline))
                        CaptureRow(row, selecting, row.id in picked,
                            click = { if (selecting) picked = if (row.id in picked) picked - row.id else picked + row.id else select(row.id) },
                            longClick = { if (!selecting) { selecting = true; picked = setOf(row.id) } })
                    }
                } else {
                    ClipDetail(clip, busy, other, number, { other = it }, { number = it }, answer, research, playbackMs) { playbackMs = it }
                }
                if (researchVisible) {
                    clip?.diagnostics?.takeIf { it.isNotBlank() }?.let { Text(it, color = KColors.Soft, fontSize = 12.sp) }
                    ResultAction("연구 모드 · 상세 확인", action = research)
                }
            }
            if (selecting && clip == null) Box(Modifier.align(Alignment.BottomCenter).padding(16.dp).fillMaxWidth()
                .clip(KShape.Panel).background(KColors.SurfaceRaised)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).pressable { if (picked.isNotEmpty()) confirmDelete = true },
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    val tint = if (picked.isEmpty()) KColors.Faint else KColors.Red
                    KIcon(KIcon.Trash, tint, size = 22.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(if (picked.isEmpty()) "삭제" else "삭제 · ${picked.size}개", color = tint, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
    if (confirmDelete) {
        val count = if (clip != null) 1 else picked.size
        Dialog(onDismissRequest = { confirmDelete = false }) {
            Column(Modifier.fillMaxWidth().clip(KShape.Panel).background(KColors.SurfaceRaised).padding(24.dp)) {
                ConfirmDialog(if (count == 1) "이 촬영을 삭제할까요?" else "${count}개 촬영을 삭제할까요?",
                    "영상과 확인한 횟수가 함께 삭제되고 되돌릴 수 없어요.", "취소", "삭제", { confirmDelete = false }) {
                    if (clip != null) deleteClip() else { deleteClips(picked); selecting = false; picked = emptySet() }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TopBar(title: String, back: () -> Unit, onTitleLongPress: () -> Unit, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp).pressable(back).semantics { contentDescription = "뒤로" }, contentAlignment = Alignment.Center) {
            KIcon(KIcon.Back, KColors.White, size = 28.dp)
        }
        Text(title, color = KColors.White, fontSize = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).combinedClickable(onClick = {}, onLongClick = onTitleLongPress).padding(horizontal = 4.dp))
        trailing()
    }
}

@Composable
private fun SelectionBar(count: Int, allSelected: Boolean, toggleAll: () -> Unit, cancel: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.heightIn(min = 48.dp).pressable(toggleAll), verticalAlignment = Alignment.CenterVertically) {
                Check(allSelected); Spacer(Modifier.width(10.dp)); Text("전체", color = KColors.White, fontSize = 15.sp)
            }
            Spacer(Modifier.weight(1f))
            Box(Modifier.heightIn(min = 48.dp).pressable(cancel).padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
                Text("취소", color = KColors.Accent, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
        }
        Text(if (count == 0) "촬영을 선택해 주세요" else "${count}개 선택됨", color = KColors.White, fontSize = 22.sp)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun Check(on: Boolean) {
    Box(Modifier.size(22.dp).clip(KShape.Control).then(if (on) Modifier.background(KColors.Accent) else Modifier.border(1.5.dp, KColors.Faint, KShape.Control)),
        contentAlignment = Alignment.Center) {
        if (on) KIcon(KIcon.Check, KColors.OnAccent, size = 16.dp)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CaptureRow(row: CaptureRowUi, selecting: Boolean, picked: Boolean, click: () -> Unit, longClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).combinedClickable(onClick = click, onLongClick = longClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (selecting) { Check(picked); Spacer(Modifier.width(14.dp)) }
        Column(Modifier.weight(1f)) {
            Text(row.name, color = KColors.White, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(row.meta, color = KColors.Muted, fontSize = 13.sp)
        }
        if (!selecting) KIcon(KIcon.Forward, KColors.Muted, size = 18.dp)
    }
}

@Composable
private fun ClipDetail(
    clip: CapturePlaybackUi, busy: Boolean, other: Boolean, number: String, setOther: (Boolean) -> Unit, setNumber: (String) -> Unit,
    answer: (Int) -> Unit, research: () -> Unit, playbackMs: Int, progress: (Int) -> Unit,
) {
    Column {
        Text(clip.confirmedCount?.let { "${it}회" } ?: "10회", color = KColors.Accent, fontSize = 64.sp, fontWeight = FontWeight.Medium)
        Text(if (clip.confirmedCount == null) "촬영 목표" else "확인한 횟수", color = KColors.Soft, fontSize = 13.sp)
    }
    if (clip.videoPath != null && clip.endMs > 0) key(clip.id, clip.videoPath) { ReferencePlayback(clip, progress) }
    else Box(Modifier.fillMaxWidth().height(260.dp).clip(KShape.Control).background(KColors.Surface).padding(16.dp), contentAlignment = Alignment.Center) {
        Text("영상을 재생할 수 없어요. 저장 상태를 확인해 주세요.", color = KColors.Soft)
    }
    Text(clip.graphTitle, color = KColors.Soft, fontSize = 13.sp)
    SignalGraph(clip, playbackMs)
    Text(if (clip.intervals.isEmpty()) "자동으로 표시한 반복 구간이 없어요. 영상을 보고 횟수를 확인해 주세요."
        else "민트 구간은 영상에서 찾은 반복 후보예요.", color = KColors.Muted, fontSize = 12.sp)
    Text("10회 했나요?", color = KColors.White, fontSize = 22.sp)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val chosen = clip.confirmedCount
        listOf("맞아요" to 10, "9회" to 9, "11회" to 11).forEach { (label, count) ->
            ResultAction(label, Modifier.weight(1f), !busy, selected = chosen == count) { answer(count) }
        }
        ResultAction("기타", Modifier.weight(1f), !busy, selected = chosen != null && chosen !in listOf(9, 10, 11)) { setOther(!other) }
    }
    if (other) {
        OutlinedTextField(number, { setNumber(it.filter(Char::isDigit).take(4)) }, label = { Text("실제 횟수") },
            modifier = Modifier.fillMaxWidth(), singleLine = true, shape = KShape.Control,
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = KColors.White, unfocusedTextColor = KColors.White,
                cursorColor = KColors.Accent, focusedBorderColor = KColors.Accent, unfocusedBorderColor = KColors.Hairline))
        ResultAction("횟수 저장", enabled = !busy && number.toIntOrNull() != null) { number.toIntOrNull()?.let(answer) }
    }
    if (clip.message.isNotBlank()) Text(clip.message, color = KColors.Soft, fontSize = 14.sp)
    val mismatch = clip.confirmedCount != null && (clip.confirmedCount != 10 ||
        clip.watchCount != null && clip.watchCount != clip.confirmedCount)
    if (mismatch) ResultAction("반복 구간 표시", enabled = !busy, action = research)
    if (busy) Text("저장 중…", color = KColors.Muted)
}

@Composable
private fun ReferencePlayback(clip: CapturePlaybackUi, progress: (Int) -> Unit) {
    var player by remember(clip.id) { mutableStateOf<VideoView?>(null) }
    var position by remember(clip.id) { mutableIntStateOf(0) }
    var playing by remember(clip.id) { mutableStateOf(true) }
    var failed by remember(clip.id) { mutableStateOf(false) }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    AndroidView(factory = { context -> VideoView(context).also { view ->
        player = view
        view.setOnPreparedListener {
            it.isLooping = true
            if (playing && owner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) view.start()
        }
        view.setOnErrorListener { _, _, _ -> failed = true; playing = false; true }
        view.setVideoURI(Uri.fromFile(java.io.File(requireNotNull(clip.videoPath))))
    } }, modifier = Modifier.fillMaxWidth().height(260.dp).clip(KShape.Control).background(KColors.Surface),
        onRelease = { it.stopPlayback() }, update = {})
    LaunchedEffect(player, clip.id, clip.endMs) {
        while (true) {
            player?.let { view ->
                position = view.currentPosition
                progress(position)
                if (position >= clip.endMs) { view.seekTo(0); if (playing && owner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) view.start() }
            }
            delay(80)
        }
    }
    DisposableEffect(owner, player) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) player?.pause()
            if (event == androidx.lifecycle.Lifecycle.Event.ON_START && playing) player?.start()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    if (failed) Text("영상을 재생하지 못했어요. 촬영 자료를 다시 열어 주세요.", color = KColors.Red)
    Text("%.1f / %.1f초".format(position / 1000.0, clip.endMs / 1000.0), color = KColors.Muted, fontSize = 12.sp)
    clip.intervals.indexOfFirst { position in it.first..it.second }.takeIf { it >= 0 }?.let {
        Text("${it + 1}회 후보", color = KColors.Accent, fontSize = 13.sp)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ResultAction(if (playing) "일시정지" else "재생", Modifier.weight(1f), !failed) {
            playing = !playing; if (playing) player?.start() else player?.pause()
        }
        ResultAction("처음부터", Modifier.weight(1f)) { player?.seekTo(0) }
    }
}

@Composable
private fun SignalGraph(clip: CapturePlaybackUi, playbackMs: Int) {
    Canvas(Modifier.fillMaxWidth().height(120.dp).clip(KShape.Control).background(KColors.Surface).padding(8.dp)) {
        val duration = clip.endMs.coerceAtLeast(1)
        fun x(ms: Int) = size.width * ms / duration
        clip.intervals.forEach { (start, end) ->
            drawRect(KColors.AccentWash, Offset(x(start), 0f), androidx.compose.ui.geometry.Size((x(end)-x(start)).coerceAtLeast(1f), size.height))
        }
        if (clip.heights.size > 1) {
            val low = clip.heights.minOf { it.second }; val span = (clip.heights.maxOf { it.second } - low).coerceAtLeast(.01f)
            val path = Path(); var last: Int? = null
            clip.heights.forEach { (ms, height) ->
                val y = size.height * (1f - (height-low)/span)
                if (last == null || ms - last!! > 500) path.moveTo(x(ms),y) else path.lineTo(x(ms),y)
                last = ms
            }
            drawPath(path, KColors.Accent, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
        }
        drawLine(KColors.White, Offset(x(playbackMs), 0f), Offset(x(playbackMs), size.height), 1.dp.toPx())
    }
}

@Composable
private fun ResultAction(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, selected: Boolean = false, action: () -> Unit) {
    Box(modifier.heightIn(min = 48.dp).clip(KShape.Control).background(if (selected) KColors.AccentWash else KColors.SurfaceRaised)
        .then(Modifier.then(if (enabled) Modifier.pressable(action) else Modifier)).padding(10.dp), contentAlignment = Alignment.Center) {
        Text(label, color = if (selected) KColors.Accent else if (enabled) KColors.White else KColors.Muted, fontSize = 14.sp)
    }
}
