package dev.kyungmin.kcoach.phone.ui

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import kotlin.math.roundToInt

/** One bottom sheet or centred dialog shown above whatever screen is current. */
class Overlay(val isSheet: Boolean, val content: @Composable (dismiss: () -> Unit) -> Unit)

class OverlayState {
    var current by mutableStateOf<Overlay?>(null)
        private set

    fun show(overlay: Overlay) {
        current = overlay
    }

    fun dismiss() {
        current = null
    }
}

@Composable
fun OverlayHost(state: OverlayState) {
    val current = state.current
    BackHandler(enabled = current != null) { state.dismiss() }
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = current != null,
            enter = fadeIn(Motion.panel()),
            exit = fadeOut(Motion.panel()),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.78f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { state.dismiss() },
            )
        }
        AnimatedContent(
            targetState = current,
            modifier = Modifier.fillMaxSize(),
            transitionSpec = { overlayTransition(initialState, targetState) },
            label = "overlay",
        ) { overlay ->
            if (overlay != null) {
                Box(Modifier.fillMaxSize(), contentAlignment = if (overlay.isSheet) Alignment.BottomCenter else Alignment.Center) {
                    if (overlay.isSheet) SheetSurface(state::dismiss) { overlay.content(state::dismiss) }
                    else DialogSurface { overlay.content(state::dismiss) }
                }
            }
        }
    }
}

private fun overlayTransition(from: Overlay?, to: Overlay?): ContentTransform = when {
    from == null && to != null ->
        (if (to.isSheet) slideInVertically(Motion.panel()) { it } else fadeIn(Motion.ui()) + scaleIn(Motion.ui(), 0.94f)) togetherWith ExitTransition.None
    from != null && to == null ->
        EnterTransition.None togetherWith
            (if (from.isSheet) slideOutVertically(Motion.panel()) { it } else fadeOut(Motion.ui()) + scaleOut(Motion.ui(), 0.94f))
    else -> fadeIn(Motion.ui()) togetherWith fadeOut(Motion.ui())
}

@Composable
private fun SheetSurface(dismiss: () -> Unit, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val dismissDistance = with(density) { 100.dp.toPx() }
    val flingVelocity = with(density) { 900.dp.toPx() }

    fun release(velocity: Float) {
        scope.launch {
            if (offset.value > dismissDistance || velocity > flingVelocity) dismiss()
            else offset.animateTo(0f, Motion.panel())
        }
    }

    // Scrollable content (long lists) hands leftover downward drags to the sheet once it is at the top.
    val nested = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < 0f && offset.value > 0f) {
                    val used = maxOf(available.y, -offset.value)
                    scope.launch { offset.snapTo(offset.value + used) }
                    return Offset(0f, used)
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (available.y > 0f && source == NestedScrollSource.UserInput) {
                    scope.launch { offset.snapTo(offset.value + available.y) }
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (offset.value > 0f) {
                    release(available.y)
                    return available
                }
                return Velocity.Zero
            }
        }
    }

    // The whole sheet is the drag handle, not just the grabber at the top.
    Column(
        Modifier
            .offset { IntOffset(0, offset.value.roundToInt()) }
            .fillMaxWidth()
            .clip(KShape.Sheet)
            .background(KColors.Surface)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .nestedScroll(nested)
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { delta -> scope.launch { offset.snapTo((offset.value + delta).coerceAtLeast(0f)) } },
                onDragStopped = { velocity -> release(velocity) },
            )
            .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        Box(Modifier.fillMaxWidth().height(36.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.width(44.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(KColors.Faint))
        }
        Column(Modifier.heightIn(max = 560.dp)) { content() }
    }
}

@Composable
private fun DialogSurface(content: @Composable () -> Unit) {
    Column(
        Modifier
            .padding(horizontal = 28.dp)
            .fillMaxWidth()
            .clip(KShape.Panel)
            .background(KColors.SurfaceRaised)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .padding(24.dp),
    ) { content() }
}

// ---- Sheet building blocks -------------------------------------------------

@Composable
fun SheetTitle(text: String) {
    Text(
        text, color = KColors.White, fontSize = 18.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = 22.dp, end = 22.dp, bottom = 14.dp),
    )
}

@Composable
fun SheetOptionRow(icon: KIcon, title: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).pressable(onClick).padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(46.dp), contentAlignment = Alignment.CenterStart) { KIcon(icon, KColors.Soft, size = 22.dp) }
        Text(title, color = KColors.White, fontSize = 14.sp, modifier = Modifier.weight(1f).padding(vertical = 6.dp))
    }
}

class SheetItem(val icon: KIcon, val title: String, val onClick: () -> Unit)

class SegOption(val label: String, val selected: Boolean, val onClick: () -> Unit)

class SegGroup(val label: String, val options: List<SegOption>)

class SheetAction(val label: String, val destructive: Boolean, val onClick: () -> Unit)

/**
 * Sheet for one set: big title, each setting as one connected bar (tap a segment to choose, no second sheet),
 * then plain text actions underneath.
 */
@Composable
fun SetSheet(title: String, subtitle: String, groups: List<SegGroup>, actions: List<SheetAction>) {
    Column(Modifier.padding(bottom = 12.dp)) {
        Column(Modifier.padding(start = 22.dp, end = 22.dp, bottom = 14.dp)) {
            Text(title, color = KColors.White, fontSize = 28.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, color = KColors.Muted, fontSize = 13.sp)
        }
        groups.forEach { group ->
            Box(Modifier.padding(horizontal = 22.dp).fillMaxWidth().height(0.5.dp).background(KColors.Hairline))
            Column(Modifier.padding(horizontal = 22.dp, vertical = 12.dp)) {
                Text(group.label, color = KColors.Muted, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth().height(42.dp).clip(KShape.Control).background(KColors.SurfaceRaised)) {
                    group.options.forEach { option ->
                        val wash by animateColorAsState(if (option.selected) KColors.AccentWash else Color.Transparent, Motion.ui(), label = "segWash")
                        val edge by animateColorAsState(if (option.selected) KColors.Accent else Color.Transparent, Motion.ui(), label = "segEdge")
                        val text by animateColorAsState(if (option.selected) KColors.Accent else KColors.Soft, Motion.ui(), label = "segText")
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .padding(2.dp)
                                .clip(KShape.Control)
                                .background(wash)
                                .border(1.dp, edge, KShape.Control)
                                .pressable(option.onClick),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(option.label, color = text, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                        }
                    }
                }
            }
        }
        Box(Modifier.padding(horizontal = 22.dp).fillMaxWidth().height(0.5.dp).background(KColors.Hairline))
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)) {
            actions.forEach { action ->
                Box(Modifier.weight(1f).height(52.dp).pressable(action.onClick), contentAlignment = Alignment.Center) {
                    Text(
                        action.label,
                        color = if (action.destructive) KColors.Red else KColors.White,
                        fontSize = 15.sp, fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

@Composable
fun SheetOptions(title: String, items: List<SheetItem>) {
    Column(Modifier.padding(bottom = 24.dp)) {
        SheetTitle(title)
        items.forEach { SheetOptionRow(it.icon, it.title, it.onClick) }
    }
}

@Composable
fun ChoiceSheet(title: String, items: List<Pair<String, String>>, onSelect: (String) -> Unit) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 20.dp)) {
        SheetTitle(title)
        items.forEach { (value, description) ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 62.dp).pressable { onSelect(value) }.padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(58.dp), contentAlignment = Alignment.CenterStart) {
                    Box(Modifier.size(20.dp).border(1.5.dp, KColors.Faint, CircleShape))
                }
                Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                    Text(value, color = KColors.White, fontSize = 17.sp)
                    if (description.isNotBlank()) Text(description, color = KColors.Muted, fontSize = 13.sp)
                }
            }
        }
    }
}

// ---- Dialog building blocks ------------------------------------------------

@Composable
private fun DialogButtons(content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.End) { content() }
}

@Composable
private fun TextAction(text: String, color: Color = KColors.Accent, onClick: () -> Unit) {
    Box(Modifier.heightIn(min = 48.dp).pressable(onClick).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
        Text(text, color = color, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    negative: String,
    positive: String,
    dismiss: () -> Unit,
    onPositive: () -> Unit,
) {
    Text(title, color = KColors.White, fontSize = 20.sp, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(12.dp))
    Text(message, color = KColors.Muted, fontSize = 15.sp)
    DialogButtons {
        TextAction(negative, KColors.Muted, dismiss)
        TextAction(positive) {
            dismiss()
            onPositive()
        }
    }
}

@Composable
fun InputDialog(title: String, hint: String, dismiss: () -> Unit, onSubmit: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Text(title, color = KColors.White, fontSize = 20.sp, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(16.dp))
    Box(Modifier.fillMaxWidth().clip(KShape.Control).background(KColors.Selected).padding(16.dp)) {
        if (text.isEmpty()) Text(hint, color = KColors.Faint, fontSize = 16.sp)
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            textStyle = TextStyle(color = KColors.White, fontSize = 16.sp),
            cursorBrush = SolidColor(KColors.Accent),
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
    }
    DialogButtons {
        TextAction("취소", KColors.Muted, dismiss)
        TextAction("추가") {
            dismiss()
            onSubmit(text.trim())
        }
    }
}

@Composable
fun PickDialog(title: String, items: List<String>, dismiss: () -> Unit, onPick: (Int) -> Unit) {
    Text(title, color = KColors.White, fontSize = 20.sp, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(8.dp))
    Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
        items.forEachIndexed { index, label ->
            Text(
                label, color = KColors.White, fontSize = 15.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .pressable {
                        dismiss()
                        onPick(index)
                    }
                    .padding(vertical = 12.dp),
            )
        }
    }
    DialogButtons { TextAction("취소", KColors.Muted, dismiss) }
}

/** Large centred call-to-action used by the start guide and the "my mountain" prompt. */
@Composable
fun BigChoiceSheet(
    eyebrow: String?,
    eyebrowColor: Color,
    title: String,
    body: String,
    bodyColor: Color,
    cancel: String,
    confirm: String,
    dismiss: () -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    filledButtons: Boolean,
) {
    Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (eyebrow == "▶") KIcon(KIcon.Play, eyebrowColor, size = 38.dp)
        else if (eyebrow != null) Text(eyebrow, color = eyebrowColor, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        Text(title, color = KColors.White, fontSize = 28.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(14.dp))
        Text(body, color = bodyColor, fontSize = 16.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val cancelModifier = Modifier.weight(1f).height(if (filledButtons) 60.dp else 58.dp)
            if (filledButtons) {
                BigButton(cancel, KColors.SurfaceRaised, KColors.White, cancelModifier) { dismiss(); onCancel() }
                BigButton(confirm, KColors.Accent, KColors.OnAccent, Modifier.weight(1f).height(60.dp)) { dismiss(); onConfirm() }
            } else {
                TextButton(cancel, cancelModifier) { dismiss(); onCancel() }
                TextButton(confirm, Modifier.weight(1f).height(58.dp)) { dismiss(); onConfirm() }
            }
        }
    }
}

@Composable
private fun BigButton(text: String, background: Color, content: Color, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.clip(KShape.Control).background(background).pressable(onClick), contentAlignment = Alignment.Center) {
        Text(text, color = content, fontSize = 18.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun TextButton(text: String, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.pressable(onClick), contentAlignment = Alignment.Center) {
        Text(text, color = KColors.Accent, fontSize = 17.sp, fontWeight = FontWeight.Medium)
    }
}
