package garden.vayne.chorus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import garden.vayne.chorus.data.HomeDashboard
import garden.vayne.chorus.data.HomeSection
import garden.vayne.chorus.designsystem.LocalChorusPalette
import garden.vayne.chorus.designsystem.R

/** A Home section's name and what it holds (F0.3). */
internal fun homeSectionText(id: String): Pair<String, String> = when (id) {
    "fronting" -> "Fronting now" to "With Switch and Log a past one"
    "quick_switch" -> "Quick switch" to "Pinned and recent members"
    "handover" -> "Handovers and reviews" to "Notes left for whoever is next"
    "waiting" -> "Waiting for you" to "Unread chats and mentions"
    "friends" -> "Friends right now" to "As each friend shares it"
    "today" -> "Today" to "Switches and posts"
    "new_from_friends" -> "New from friends" to "Latest posts and entries"
    "write" -> "Write something" to "A new post"
    else -> id to ""
}

/**
 * F0.3 *Home sections*: drag a row by its grip to reorder, switch one off to hide it; Reset goes
 * back to the default order, Done saves for whoever is reading (each member keeps their own).
 */
@Composable
internal fun HomeSectionsDrawer(sections: List<HomeSection>, readerName: String?, onDismiss: () -> Unit, onSave: (List<HomeSection>) -> Unit) {
    val p = LocalChorusPalette.current
    val person = sections.none { it.id == "fronting" }
    var draft by remember { mutableStateOf(HomeDashboard.editable(sections)) }
    var dragging by remember { mutableStateOf<String?>(null) }
    var dragY by remember { mutableFloatStateOf(0f) }
    val rowPx = with(LocalDensity.current) { 56.dp.toPx() }
    fun move(id: String, by: Int) {
        val i = draft.indexOfFirst { it.id == id }
        val j = (i + by).coerceIn(0, draft.lastIndex)
        if (i < 0 || i == j) return
        draft = draft.toMutableList().apply { add(j, removeAt(i)) }
    }
    fun toggle(id: String) { draft = draft.map { if (it.id == id) it.copy(on = !it.on) else it } }
    Drawer(onDismiss, "Home sections", readerName?.let { "$it’s Home" }, scroll = false, footer = {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DrawerButton("Reset", { draft = HomeDashboard.editable(HomeDashboard.parse(null, person)) }, kind = ButtonKind.Neutral,
                modifier = Modifier.width(88.dp))
            DrawerButton("Done", { onSave(HomeDashboard.parse(HomeDashboard.value(draft), person)); onDismiss() }, modifier = Modifier.weight(1f))
        }
    }) {
        Column {
            draft.forEachIndexed { index, s -> androidx.compose.runtime.key(s.id) {
                val (title, about) = homeSectionText(s.id)
                val active = dragging == s.id
                Row(Modifier.fillMaxWidth().height(56.dp).zIndex(if (active) 1f else 0f)
                    .graphicsLayer { translationY = if (active) dragY else 0f }
                    .clip(RoundedCornerShape(12.dp)).background(if (active) p.surface3 else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable(role = Role.Switch, onClickLabel = if (s.on) "Hide $title" else "Show $title") { toggle(s.id) },
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(40.dp).semantics {
                        contentDescription = "Drag $title"
                        customActions = buildList {
                            if (index > 0) add(CustomAccessibilityAction("Move earlier") { move(s.id, -1); true })
                            if (index < draft.lastIndex) add(CustomAccessibilityAction("Move later") { move(s.id, 1); true })
                        }
                    }.pointerInput(s.id) {
                        detectDragGestures(onDragStart = { dragging = s.id; dragY = 0f },
                            onDragEnd = { dragging = null; dragY = 0f }, onDragCancel = { dragging = null; dragY = 0f }) { change, amount ->
                            change.consume()
                            dragY += amount.y
                            while (dragY > rowPx / 2 && draft.indexOfFirst { it.id == s.id } < draft.lastIndex) { move(s.id, 1); dragY -= rowPx }
                            while (dragY < -rowPx / 2 && draft.indexOfFirst { it.id == s.id } > 0) { move(s.id, -1); dragY += rowPx }
                        }
                    }, contentAlignment = Alignment.Center) { Glyph(R.drawable.ic_grip, p.ink3, 18.dp) }
                    Column(Modifier.weight(1f)) {
                        Text(title, color = if (s.on) p.ink else p.ink3, fontSize = 15.sp, lineHeight = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(about, color = p.ink3, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    ChorusSwitch(s.on, { toggle(s.id) }, modifier = Modifier.padding(end = 12.dp))
                }
            } }
        }
    }
}
