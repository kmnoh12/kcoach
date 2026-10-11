package dev.kyungmin.kcoach.phone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class EditTarget(val exerciseIndex: Int, val setIndex: Int, val field: SetField)

/**
 * In-screen number keypad. Edits apply live; `<` `>` walk the fields,
 * `−` `+` `−5` `+5` and ← repeat while held, ✓ confirms and completes the set.
 */
@Composable
fun WorkoutKeypad(
    field: SetField,
    applyAll: Boolean,
    usePounds: Boolean,
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    onDelta: (Double) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onDone: () -> Unit,
    onToggleAll: () -> Unit,
    onToggleUnit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().background(KColors.Bg).padding(horizontal = 6.dp, vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Segmented(listOf("전체" to applyAll, "1세트" to !applyAll), onToggleAll)
            Box(Modifier.weight(1f))
            if (field == SetField.WEIGHT) Segmented(listOf("kg" to !usePounds, "lb" to usePounds), onToggleUnit)
        }
        KeyRow {
            Key("1") { onDigit('1') }; Key("2") { onDigit('2') }; Key("3") { onDigit('3') }
            Key("‹", tone = KeyTone.Nav) { onPrevious() }; Key("›", tone = KeyTone.Nav) { onNext() }
        }
        KeyRow {
            Key("4") { onDigit('4') }; Key("5") { onDigit('5') }; Key("6") { onDigit('6') }
            Key("−", tone = KeyTone.Nav, repeat = true) { onDelta(-1.0) }
            Key("+", tone = KeyTone.Nav, repeat = true) { onDelta(1.0) }
        }
        KeyRow {
            Key("7") { onDigit('7') }; Key("8") { onDigit('8') }; Key("9") { onDigit('9') }
            Key("−5", tone = KeyTone.Nav, repeat = true) { onDelta(-5.0) }
            Key("+5", tone = KeyTone.Nav, repeat = true) { onDelta(5.0) }
        }
        KeyRow {
            Key(if (field == SetField.WEIGHT) "." else "") { if (field == SetField.WEIGHT) onDigit('.') }
            Key("0") { onDigit('0') }
            Key("⌫", repeat = true) { onBackspace() }
            Key("✓", tone = KeyTone.Done, weight = 2f) { onDone() }
        }
    }
}

private enum class KeyTone { Number, Nav, Done }

@Composable
private fun KeyRow(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(54.dp).padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

@Composable
private fun RowScope.Key(
    label: String,
    tone: KeyTone = KeyTone.Number,
    weight: Float = 1f,
    repeat: Boolean = false,
    onPress: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val action = rememberUpdatedState(onPress)
    val background = when (tone) {
        KeyTone.Number -> KColors.SurfaceRaised
        KeyTone.Nav -> KColors.Surface
        KeyTone.Done -> KColors.AmberDark
    }
    Box(
        Modifier
            .weight(weight)
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .pointerInput(repeat) {
                detectTapGestures(onPress = {
                    action.value()
                    // Holding a repeat key keeps stepping, like a hardware key.
                    val job = if (repeat) scope.launch {
                        delay(420)
                        while (true) {
                            action.value()
                            delay(70)
                        }
                    } else null
                    tryAwaitRelease()
                    job?.cancel()
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = KColors.White,
            fontSize = if (label.length > 1 && tone != KeyTone.Done) 18.sp else 22.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun Segmented(options: List<Pair<String, Boolean>>, onToggle: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(16.dp)).background(KColors.Surface)
            .pointerInput(Unit) { detectTapGestures { onToggle() } },
    ) {
        options.forEach { (text, selected) ->
            Text(
                text,
                color = if (selected) KColors.Amber else KColors.Muted,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (selected) Color(0xFF322A12) else Color.Transparent)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}
