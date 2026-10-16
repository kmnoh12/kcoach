package dev.kyungmin.kcoach.phone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kyungmin.kcoach.phone.*
import java.time.LocalDate

@Composable
internal fun ProfileEditor(profile: WorkoutProfile, onDismiss: () -> Unit,
    extraContent: @Composable () -> Unit = {}, save: (WorkoutProfile) -> Unit) {
    var age by rememberSaveable { mutableStateOf(profile.ageYears?.toString().orEmpty()) }
    var energy by rememberSaveable { mutableStateOf(profile.calorieBasis.isNotBlank()) }
    var height by rememberSaveable { mutableStateOf(profile.heightCm?.toString().orEmpty()) }
    var weight by rememberSaveable { mutableStateOf(profile.weightKg?.toString().orEmpty()) }
    var measured by rememberSaveable { mutableStateOf(profile.weightMeasuredOn.ifBlank { LocalDate.now().toString() }) }
    var fat by rememberSaveable { mutableStateOf(profile.bodyFatPercent?.toString().orEmpty()) }
    var gender by rememberSaveable { mutableStateOf(profile.gender) }
    var muscle by rememberSaveable { mutableStateOf(profile.skeletalMuscleKg?.toString().orEmpty()) }
    var goal by rememberSaveable { mutableStateOf(profile.goal) }
    var experience by rememberSaveable { mutableStateOf(profile.experience) }
    var constraints by rememberSaveable { mutableStateOf(profile.constraints) }
    var equipment by rememberSaveable { mutableStateOf(profile.equipment) }
    var error by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().background(KColors.Bg).safeDrawingPadding().imePadding().padding(20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onDismiss) { KIcon(KIcon.Back, KColors.White) }
            Text("내 운동 프로필", color = KColors.White, fontSize = 25.sp)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("모든 항목은 선택이에요. 입력하지 않은 정보는 추정해 채우지 않아요.", color = KColors.Muted, fontSize = 13.sp)
            ProfileField("나이 (선택, 세션 평균 칼로리 기준용)", age, true) { age = it }
            Text("성별", color = KColors.Soft, fontSize = 13.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("" to "미입력", "MALE" to "남성", "FEMALE" to "여성", "OTHER" to "기타").forEach { (value, label) ->
                    OutlinedButton(onClick = { gender = value }, modifier = Modifier.weight(1f), shape = KShape.Control,
                        border = androidx.compose.foundation.BorderStroke(1.dp, if (gender == value) KColors.Accent else KColors.Hairline)) {
                        Text(label, color = if (gender == value) KColors.Accent else KColors.Soft, fontSize = 12.sp)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(energy, onCheckedChange = { energy = it })
                Text("다종목 8~15회 일반 근력운동 기준으로 활동 칼로리 추정", color = KColors.Soft, fontSize = 13.sp)
            }
            Text("19~59세·운동일 전 90일 이내 체중에 3.5 MET 세션 평균을 적용해요. 개인 실측 칼로리가 아니에요.", color = KColors.Muted, fontSize = 12.sp)
            ProfileField("키 (cm)", height, true) { height = it }
            ProfileField("체중 (kg)", weight, true) { weight = it }
            ProfileField("체중 측정일 (YYYY-MM-DD)", measured) { measured = it }
            ProfileField("체지방률 (%)", fat, true) { fat = it }
            ProfileField("골격근량 (kg, 선택)", muscle, true) { muscle = it }
            extraContent()
            ProfileField("목표", goal) { goal = it }
            ProfileField("운동 경력", experience) { experience = it }
            ProfileField("제약·주의할 점", constraints) { constraints = it }
            ProfileField("사용 기구·증량 단위", equipment) { equipment = it }
            if (error.isNotBlank()) Text(error, color = KColors.Red, fontSize = 13.sp)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss) { Text("취소") }
            TextButton(onClick = {
                try {
                    fun number(value: String) = value.trim().takeIf { it.isNotEmpty() }?.toDouble()
                    val draft = profile.copy(ageYears = if (age.isBlank()) null else age.trim().toInt(), calorieBasis = if (energy) "RESISTANCE_MULTIPLE_8_15" else "", heightCm = number(height), weightKg = number(weight),
                        weightMeasuredOn = if (weight.isBlank()) "" else measured.trim(), bodyFatPercent = number(fat),
                        goal = goal.trim(), experience = experience.trim(), constraints = constraints.trim(), equipment = equipment.trim(),
                        gender = gender, skeletalMuscleKg = number(muscle))
                    save(draft)
                } catch (_: Exception) { error = "입력 숫자·체중 측정일과 워치 착용 상태를 확인해 주세요. 저장 중 다른 화면에서 바뀌었다면 다시 열어 주세요" }
            }) { Text("저장") }
        }
    }
}

@Composable
private fun ProfileField(label: String, value: String, decimal: Boolean = false, change: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = { if (it.length <= 2_000) change(it) }, label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Text),
        modifier = Modifier.fillMaxWidth(), singleLine = decimal, maxLines = if (decimal) 1 else 4)
}

