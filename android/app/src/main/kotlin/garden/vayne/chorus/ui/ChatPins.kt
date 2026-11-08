package garden.vayne.chorus.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import garden.vayne.chorus.designsystem.LocalChorusPalette
import kotlinx.coroutines.isActive

/**
 * Arrange pinned chats by dragging their handles (D-097); the same drag mechanics as Reorder
 * members (MemberReorder.kt), with Earlier/Later for accessibility. Nothing is saved until Done.
 */
@Composable
internal fun ArrangePins(pinned: List<ChatListRow>, modifier: Modifier, onCancel: () -> Unit, onDone: (List<String>) -> Unit) {
    val p = LocalChorusPalette.current
    val rows = remember(pinned) { pinned.associateBy { it.channel.id } }
    var ids by rememberSaveable { mutableStateOf(pinned.map { it.channel.id }) }
    var dragging by remember { mutableStateOf<String?>(null) }
    var dragY by remember { mutableFloatStateOf(0f) }
    var fingerY by remember { mutableFloatStateOf(0f) }
    var beforeDrag by remember { mutableStateOf<List<String>>(emptyList()) }
    val list = rememberLazyListState()
    val motion = LocalChorusMotionOverride.current ?: android.animation.ValueAnimator.areAnimatorsEnabled()
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val latestIds by rememberUpdatedState(ids)
    fun cancelDrag() { if (dragging != null) { ids = beforeDrag; dragging = null; dragY = 0f } }
    BackHandler { if (dragging != null) cancelDrag() else onCancel() }
    fun move(id: String, target: Int) {
        val current = ids.indexOf(id)
        if (current < 0 || target !in ids.indices || target == current) return
        ids = ids.toMutableList().apply { add(target, removeAt(current)) }
    }
    fun updateTarget() {
        val id = dragging ?: return
        val index = ids.indexOf(id)
        val row = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id } ?: return
        val target = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key != id && fingerY in it.offset.toFloat()..(it.offset + it.size).toFloat() }
        if (target != null) {
            val to = (target.key as? String)?.let(ids::indexOf) ?: -1
            val middle = target.offset + target.size / 2
            if (to >= 0 && ((to > index && fingerY > middle) || (to < index && fingerY < middle))) move(id, to)
        }
        dragY = fingerY - row.offset - row.size / 2f
    }
    // edge scroll while a held row sits near the top or bottom
    LaunchedEffect(dragging) {
        if (dragging == null) return@LaunchedEffect
        var previous = 0L
        while (isActive) {
            var step = 0f
            withFrameNanos { now ->
                val dt = if (previous == 0L) 0f else ((now - previous) / 1_000_000_000f).coerceAtMost(.05f)
                previous = now
                val info = list.layoutInfo
                val edge = 56f * density
                val speed = when {
                    fingerY < info.viewportStartOffset + edge -> -((info.viewportStartOffset + edge - fingerY) / edge).coerceIn(0f, 1f)
                    fingerY > info.viewportEndOffset - edge -> ((fingerY - (info.viewportEndOffset - edge)) / edge).coerceIn(0f, 1f)
                    else -> 0f
                }
                step = speed * 400f * density * dt
            }
            if (step != 0f) list.scrollBy(step)
            updateTarget()
        }
    }
    Column(modifier.fillMaxHeight().background(p.bg)) {
        Row(Modifier.fillMaxWidth().background(p.surface).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onCancel) { Text("Cancel") }
            Text("Pinned chats", color = p.ink, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Button(enabled = dragging == null, onClick = { onDone(ids) }) { Text("Done") }
        }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp).padding(top = 8.dp), state = list,
            verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(ids, key = { it }) { id ->
                val row = rows[id] ?: return@items
                val active = dragging == id
                Row(Modifier.fillMaxWidth().animateItem(placementSpec = if (motion && !active) tween(180) else null)
                    .zIndex(if (active) 1f else 0f).graphicsLayer { translationY = if (active) dragY else 0f }
                    .clip(RoundedCornerShape(14.dp)).background(if (active) p.surface3 else p.surface)
                    .padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    ChatRowDisc(row, 40.dp)
                    Column(Modifier.weight(1f).padding(start = 12.dp, top = 8.dp, bottom = 8.dp)) {
                        Text(row.title, color = p.ink, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row {
                            TextButton(enabled = dragging == null && ids.indexOf(id) > 0, onClick = { move(id, ids.indexOf(id) - 1) }) { Text("Earlier") }
                            TextButton(enabled = dragging == null && ids.indexOf(id) < ids.lastIndex, onClick = { move(id, ids.indexOf(id) + 1) }) { Text("Later") }
                        }
                    }
                    val ink = p.ink2
                    Canvas(Modifier.size(48.dp).semantics {
                        contentDescription = "Drag ${row.title}"
                        customActions = buildList {
                            if (dragging == null && ids.indexOf(id) > 0) add(CustomAccessibilityAction("Move earlier") { move(id, ids.indexOf(id) - 1); true })
                            if (dragging == null && ids.indexOf(id) < ids.lastIndex) add(CustomAccessibilityAction("Move later") { move(id, ids.indexOf(id) + 1); true })
                        }
                    }.pointerInput(id) {
                        detectDragGestures(onDragStart = {
                            val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id }
                            if (item != null) {
                                beforeDrag = latestIds; dragging = id
                                fingerY = item.offset + item.size / 2f; dragY = 0f
                            }
                        }, onDragEnd = { dragging = null; dragY = 0f }, onDragCancel = { cancelDrag() }) { change, amount ->
                            change.consume(); fingerY += amount.y; updateTarget()
                        }
                    }) {
                        for (y in listOf(.38f, .5f, .62f)) drawLine(ink, Offset(size.width * .3f, size.height * y),
                            Offset(size.width * .7f, size.height * y), strokeWidth = 2.dp.toPx())
                    }
                }
            }
        }
    }
}
