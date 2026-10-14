package dev.kyungmin.kcoach.phone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kyungmin.kcoach.phone.ChatGptConnectionUi
import dev.kyungmin.kcoach.phone.R

class ChatGptAccountActions(
    val back: () -> Unit,
    val connect: (usePlan: Boolean, newAccount: Boolean) -> Unit,
    val cancel: () -> Unit,
    val signOut: () -> Unit,
    val select: (String) -> Unit,
    val manageUsage: () -> Unit,
    val manageConnections: () -> Unit,
    val welcomeSeen: () -> Unit,
)

@Composable
fun ChatGptAccountScreen(ui: ChatGptConnectionUi, actions: ChatGptAccountActions) {
    var usePlan by rememberSaveable { mutableStateOf(true) }
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }
    val enabled = !ui.loading && !ui.busy
    MaterialTheme(colorScheme = darkColorScheme(primary = KColors.Accent, onPrimary = KColors.OnAccent,
        background = KColors.Bg, surface = KColors.Surface, onSurface = KColors.White, outline = KColors.Hairline)) {
        Column(Modifier.fillMaxSize().background(KColors.Bg).safeDrawingPadding()
            .verticalScroll(rememberScrollState()).padding(20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = actions.back) { KIcon(KIcon.Back, KColors.White) }
                Text("ChatGPT 연결", color = KColors.White, fontSize = 24.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(24.dp))
            Text(if (ui.connected) ui.accountLabel else "내 ChatGPT 계정으로 연결", color = KColors.White, fontSize = 21.sp)
            Spacer(Modifier.height(10.dp))
            Text("ChatGPT Plus·Pro의 구독 한도를 이 앱의 AI 요청에 사용할 수 있어요. 로그인과 한도 사용은 별도 권한이에요.", color = KColors.Soft, fontSize = 14.sp)
            Spacer(Modifier.height(20.dp))
            if (ui.connected) {
                SectionCard("연결 상태", listOf(
                    "계정" to "로그인 완료",
                    "구독 한도 사용" to if (ui.planAllowed) "허용됨" else "허용되지 않음",
                ))
                Spacer(Modifier.height(16.dp))
            }
            Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { usePlan = !usePlan }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = usePlan, onCheckedChange = { usePlan = it }, enabled = enabled,
                    colors = CheckboxDefaults.colors(checkedColor = KColors.Accent))
                Text("연결할 때 구독 한도 사용도 요청", color = KColors.White, fontSize = 14.sp)
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { actions.connect(usePlan, false) }, enabled = enabled,
                modifier = Modifier.fillMaxWidth().height(54.dp), shape = KShape.Control,
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
            ) {
                Image(painterResource(R.drawable.ic_chatgpt), contentDescription = null, modifier = Modifier.size(21.dp))
                Spacer(Modifier.width(10.dp))
                Text("Continue with ChatGPT", fontSize = 16.sp)
            }
            if (ui.signingIn) {
                TextButton(onClick = actions.cancel, modifier = Modifier.fillMaxWidth()) { Text("로그인 취소") }
            }
            if (ui.message.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(ui.message, color = KColors.Soft, fontSize = 14.sp)
            }
            Spacer(Modifier.height(20.dp))
            Text("로그인만으로 한도가 소모되지는 않아요. GPT 분석 기능은 아직 준비 중이에요.", color = KColors.Muted, fontSize = 13.sp)
            Spacer(Modifier.height(24.dp))
            OutlinedButton(onClick = actions.manageUsage, modifier = Modifier.fillMaxWidth().height(50.dp), shape = KShape.Control) {
                Text("ChatGPT 사용량 관리", color = KColors.White)
            }
            TextButton(onClick = actions.manageConnections, modifier = Modifier.fillMaxWidth()) { Text("ChatGPT에서 앱 연결 관리") }
            if (ui.profiles.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                Text("저장된 계정", color = KColors.Muted, fontSize = 13.sp)
                ui.profiles.forEachIndexed { index, profile ->
                    TextButton(onClick = { actions.select(profile.id) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                        Text("${index + 1}. ${profile.label}${if (profile.id == ui.activeProfileId) " · 선택됨" else ""}",
                            color = if (profile.id == ui.activeProfileId) KColors.Accent else KColors.Soft)
                    }
                }
                TextButton(onClick = { actions.connect(usePlan, true) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("다른 계정으로 연결") }
            }
            if (ui.connected) {
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = { confirmSignOut = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                    Text("이 기기에서 로그아웃", color = KColors.Soft)
                }
            }
        }
        if (ui.welcome) {
            AlertDialog(
                onDismissRequest = actions.welcomeSeen,
                title = { Text("구독 한도 사용을 허용했어요") },
                text = { Text("앞으로 이 앱에서 GPT 분석을 요청하면 기존 Codex·ChatGPT Work 한도가 소모돼요. ChatGPT 설정에서 앱별 한도를 관리할 수 있어요.") },
                confirmButton = { TextButton(onClick = actions.welcomeSeen) { Text("확인") } },
            )
        }
        if (confirmSignOut) {
            AlertDialog(
                onDismissRequest = { confirmSignOut = false }, title = { Text("이 기기에서 로그아웃") },
                text = { Text("이 기기의 인증 정보를 지워요. 운동 기록과 개인 보정은 유지돼요.") },
                confirmButton = { TextButton(onClick = { confirmSignOut = false; actions.signOut() }) { Text("로그아웃") } },
                dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("취소") } },
            )
        }
    }
}
