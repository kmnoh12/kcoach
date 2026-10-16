package dev.kyungmin.kcoach.phone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class SettingsActions(val back: () -> Unit, val profile: () -> Unit = {}, val tempo: () -> Unit = {}, val posture: () -> Unit = {})

@Composable
fun SettingsScreen(actions: SettingsActions) {
    MaterialTheme(colorScheme = darkColorScheme(primary = KColors.Accent, onPrimary = KColors.OnAccent,
        background = KColors.Bg, surface = KColors.Surface, onSurface = KColors.White, outline = KColors.Hairline)) {
        Column(Modifier.fillMaxSize().background(KColors.Bg).safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = actions.back) { KIcon(KIcon.Back, KColors.White) }
                Text("설정", color = KColors.White, fontSize = 28.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(28.dp))
            OutlinedButton(onClick = actions.profile, modifier = Modifier.fillMaxWidth().height(50.dp), shape = KShape.Control) {
                Text("내 운동 프로필 · 워치 착용", color = KColors.White)
            }
            Text("신체 정보와 평소 착용 상태를 폰에 저장해요. 로그인 없이 설정할 수 있어요.", color = KColors.Muted, fontSize = 13.sp)
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = actions.posture, modifier = Modifier.fillMaxWidth().height(50.dp), shape = KShape.Control) {
                Text("운동별 자세", color = KColors.White)
            }
            Text("운동마다 평소 하는 그립이나 시작 자세를 지정해요.", color = KColors.Muted, fontSize = 13.sp)
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = actions.tempo, modifier = Modifier.fillMaxWidth().height(50.dp), shape = KShape.Control) {
                Text("운동별 템포 알림", color = KColors.White)
            }
        }
    }
}
