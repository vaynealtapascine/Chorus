package garden.vayne.chorus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import garden.vayne.chorus.data.DeviceNavigation
import garden.vayne.chorus.designsystem.LocalChorusPalette
import garden.vayne.chorus.designsystem.R

internal fun destinationLabel(name: String) = when(name) {
    "Home" -> "Fronting"; "Today" -> "Home"; "People" -> "Friends"; "History" -> "Front history"; else -> name
}
internal fun destinationIcon(name: String) = when(name) {
    "Home" -> AppIcon.Front; "Today" -> AppIcon.Home; "People" -> AppIcon.Friends; "History" -> AppIcon.History
    "Members" -> AppIcon.Members; "Chat" -> AppIcon.Chat; "Journal" -> AppIcon.Journal; "Notifications" -> AppIcon.Bell; else -> AppIcon.More
}

/**
 * Edit bottom bar, in Home sections' form (F0.3): the bar as it will look on top, then every
 * destination with a grip to reorder and a switch (four at most; More always ends the bar).
 * Reset goes back to the default bar, Done saves it on this device.
 */
@Composable
internal fun BottomBarDrawer(selected: List<String>, person: Boolean, onDismiss: () -> Unit, onSave: (List<String>) -> Unit) {
    val p = LocalChorusPalette.current
    var draft by remember { mutableStateOf(DeviceNavigation.editable(selected, person)) }
    var dragging by remember { mutableStateOf<String?>(null) }
    var dragY by remember { mutableFloatStateOf(0f) }
    val rowPx = with(LocalDensity.current) { 52.dp.toPx() }
    val bar = DeviceNavigation.chosen(draft)
    fun move(name: String, by: Int) {
        val i = draft.indexOfFirst { it.first == name }
        val j = (i + by).coerceIn(0, draft.lastIndex)
        if (i < 0 || i == j) return
        draft = draft.toMutableList().apply { add(j, removeAt(i)) }
    }
    fun toggle(name: String) {
        draft = draft.map { (n, on) -> if (n == name && (on || bar.size < DeviceNavigation.MAX)) n to !on else n to on }
    }
    Drawer(onDismiss, "Bottom bar", scroll = false, footer = {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DrawerButton("Reset", { draft = DeviceNavigation.editable(DeviceNavigation.resolve(null, person), person) }, kind = ButtonKind.Neutral,
                modifier = Modifier.width(88.dp))
            DrawerButton("Done", { onSave(bar); onDismiss() }, modifier = Modifier.weight(1f))
        }
    }) {
        // the bar as it will be
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(cardColor()).padding(vertical = 10.dp)) {
            (bar + "More").forEach { name ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    ChorusIcon(destinationIcon(name))
                    Text(destinationLabel(name), color = p.ink2, fontSize = 11.sp, lineHeight = 14.sp, textAlign = TextAlign.Center,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Column {
            draft.forEachIndexed { index, (name, on) -> key(name) {
                val label = destinationLabel(name)
                val active = dragging == name
                val canAdd = on || bar.size < DeviceNavigation.MAX
                Row(Modifier.fillMaxWidth().height(52.dp).zIndex(if (active) 1f else 0f)
                    .graphicsLayer { translationY = if (active) dragY else 0f }
                    .clip(RoundedCornerShape(12.dp)).background(if (active) p.surface3 else Color.Transparent)
                    .clickable(enabled = canAdd, role = Role.Switch, onClickLabel = if (on) "Take $label off the bar" else "Put $label on the bar") { toggle(name) },
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(40.dp).semantics {
                        contentDescription = "Drag $label"
                        customActions = buildList {
                            if (index > 0) add(CustomAccessibilityAction("Move earlier") { move(name, -1); true })
                            if (index < draft.lastIndex) add(CustomAccessibilityAction("Move later") { move(name, 1); true })
                        }
                    }.pointerInput(name) {
                        detectDragGestures(onDragStart = { dragging = name; dragY = 0f },
                            onDragEnd = { dragging = null; dragY = 0f }, onDragCancel = { dragging = null; dragY = 0f }) { change, amount ->
                            change.consume()
                            dragY += amount.y
                            while (dragY > rowPx / 2 && draft.indexOfFirst { it.first == name } < draft.lastIndex) { move(name, 1); dragY -= rowPx }
                            while (dragY < -rowPx / 2 && draft.indexOfFirst { it.first == name } > 0) { move(name, -1); dragY += rowPx }
                        }
                    }, contentAlignment = Alignment.Center) { Glyph(R.drawable.ic_grip, p.ink3, 18.dp) }
                    ChorusIcon(destinationIcon(name))
                    Text(label, color = if (on) p.ink else p.ink3, fontSize = 15.sp, lineHeight = 20.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    ChorusSwitch(on, { toggle(name) }, enabled = canAdd, modifier = Modifier.padding(end = 12.dp))
                }
            } }
        }
    }
}
