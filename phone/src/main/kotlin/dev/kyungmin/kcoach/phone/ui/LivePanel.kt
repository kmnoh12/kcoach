package dev.kyungmin.kcoach.phone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One status line of the watch panel. Observable text and color; [tag] tells connection text from live telemetry. */
class LiveLine(text: String, var tag: String?) {
    var text by mutableStateOf(text)
    var color by mutableStateOf(KColors.Accent)

    fun setTextColor(argb: Int) {
        color = Color(argb)
    }
}

@Composable
fun LivePanel(title: LiveLine, telemetry: LiveLine, modifier: Modifier = Modifier) {
    Row(
        modifier
            .height(60.dp)
            .clip(KShape.Control)
            .background(KColors.Surface)
            .border(0.5.dp, KColors.Hairline, KShape.Control)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KIcon(KIcon.Watch, title.color, size = 22.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title.text, color = title.color, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            Text(telemetry.text, color = telemetry.color, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
