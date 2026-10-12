package dev.kyungmin.kcoach.phone.ui

import android.view.View
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.kyungmin.kcoach.phone.ExerciseDefinition

class LibraryActions(
    val back: () -> Unit,
    val custom: () -> Unit,
    val add: (ExerciseDefinition) -> Unit,
)

private val Modes = listOf("전체", "좋아하는", "자주하는", "최근", "커스텀")
private val Bodies = listOf("전체", "가슴", "등", "어깨", "삼두", "이두", "하체")
private val Equipments = listOf("전체", "맨몸", "덤벨", "바벨", "머신", "케이블")

@Composable
fun LibraryScreen(
    catalog: List<ExerciseDefinition>,
    frequent: Set<String>,
    actions: LibraryActions,
    thumbnail: (ExerciseDefinition) -> View,
) {
    var mode by remember { mutableStateOf("전체") }
    var body by remember { mutableStateOf("전체") }
    var equipment by remember { mutableStateOf("전체") }
    var query by remember { mutableStateOf("") }
    val filtered = catalog
        .filter { item ->
            (query.isBlank() || item.name.contains(query, ignoreCase = true)) &&
                (body == "전체" || item.bodyCategory == body) &&
                (equipment == "전체" || item.equipment == equipment) &&
                when (mode) {
                    "좋아하는" -> item.favorite
                    "자주하는" -> item.id in frequent
                    "커스텀" -> item.id.startsWith("custom_")
                    else -> true
                }
        }
        .sortedWith(compareByDescending<ExerciseDefinition> { it.favorite }.thenBy { it.name })

    Column(Modifier.fillMaxSize().background(KColors.Bg).padding(start = 18.dp, end = 18.dp, top = 14.dp)) {
        Row(Modifier.fillMaxWidth().height(58.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(54.dp, 58.dp).pressable(actions.back), contentAlignment = Alignment.Center) {
                Text("‹", color = KColors.White, fontSize = 48.sp)
            }
            Box(
                Modifier.weight(1f).height(58.dp).clip(KShape.Control).background(KColors.SurfaceRaised).padding(horizontal = 18.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (query.isEmpty()) Text("운동 검색", color = KColors.Muted, fontSize = 17.sp)
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(color = KColors.White, fontSize = 17.sp),
                    cursorBrush = SolidColor(KColors.Accent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.width(12.dp))
            Box(Modifier.size(48.dp, 58.dp).pressable(actions.custom), contentAlignment = Alignment.Center) {
                Text("⊞", color = KColors.White, fontSize = 34.sp)
            }
        }
        Spacer(Modifier.height(18.dp))
        ChipStrip(Modes, mode) { mode = it }
        Spacer(Modifier.height(10.dp))
        ChipStrip(Bodies, body) { body = it }
        Spacer(Modifier.height(10.dp))
        ChipStrip(Equipments, equipment) { equipment = it }
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 20.dp)) {
            item(key = "custom") {
                Text(
                    "＋  커스텀 운동 추가", color = KColors.Accent, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth().pressable(actions.custom).padding(14.dp, 16.dp),
                )
            }
            item(key = "count") {
                Text("${filtered.size}개 운동", color = KColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(start = 6.dp, top = 8.dp, bottom = 12.dp))
            }
            items(filtered, key = { it.id }) { exercise ->
                Row(
                    Modifier.fillMaxWidth().pressable { actions.add(exercise) }.padding(horizontal = 4.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (exercise.favorite) "♥" else "♡",
                        color = if (exercise.favorite) Color(0xFFE06687) else KColors.Faint,
                        fontSize = 18.sp, textAlign = TextAlign.Center, modifier = Modifier.width(30.dp),
                    )
                    AndroidView(factory = { thumbnail(exercise) }, modifier = Modifier.size(56.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(exercise.name, color = KColors.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(2.dp))
                        Text(exercise.muscles, color = KColors.Muted, fontSize = 12.sp)
                    }
                    Text("•••", color = KColors.Muted, fontSize = 15.sp)
                }
            }
        }
    }
}

@Composable
private fun ChipStrip(values: List<String>, selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        values.forEach { value ->
            val isSelected = value == selected
            val background by animateColorAsState(if (isSelected) KColors.AccentWash else Color.Transparent, Motion.ui(), label = "chipBg")
            val text by animateColorAsState(if (isSelected) KColors.Accent else KColors.Muted, Motion.ui(), label = "chipText")
            Text(
                value, color = text, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(KShape.Control)
                    .background(background)
                    .pressable { onSelect(value) }
                    .padding(horizontal = 14.dp, vertical = 9.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
    }
}
