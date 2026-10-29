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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import garden.vayne.chorus.data.*
import garden.vayne.chorus.designsystem.LocalChorusPalette
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
internal fun MemberReorder(chorus: Chorus, model: Model, onClose: () -> Unit) {
    val p = LocalChorusPalette.current
    val account = chorus.device?.accountId
    val initial = remember(model.members, account) { MemberOrdering.eligible(model.members, account) }
    val baseline by rememberSaveable { mutableStateOf(initial.associate { it.id to MemberOrdering.key(it) }) }
    var ids by rememberSaveable { mutableStateOf(initial.map { it.id }) }
    var moved by rememberSaveable { mutableStateOf(listOf<String>()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var dragging by rememberSaveable { mutableStateOf<String?>(null) }
    var dragY by remember { mutableFloatStateOf(0f) }
    var fingerY by remember { mutableFloatStateOf(0f) }
    var beforeDrag by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var beforeMoved by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    val list = rememberLazyListState()
    val actions = rememberCoroutineScope()
    val motion = LocalChorusMotionOverride.current ?: android.animation.ValueAnimator.areAnimatorsEnabled()
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val latestIds by rememberUpdatedState(ids)
    val latestModel by rememberUpdatedState(model)
    fun cancelDrag() { if (dragging != null) { ids = beforeDrag; moved = beforeMoved; dragging = null; dragY = 0f } }
    LaunchedEffect(Unit) { cancelDrag() } // recreation cancels the interrupted drop, retaining earlier draft moves
    BackHandler { if (dragging != null) cancelDrag() else if (!busy) onClose() }
    fun move(id: String, target: Int) {
        val current = ids.indexOf(id)
        if (current < 0 || target !in ids.indices || target == current) return
        ids = ids.toMutableList().apply { add(target, removeAt(current)) }
        moved = (moved + id).distinct()
    }
    fun updateTarget() {
        val id = dragging ?: return
        val index = ids.indexOf(id)
        val row = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id } ?: return
        val center = fingerY
        val target = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key != id && center in it.offset.toFloat()..(it.offset + it.size).toFloat() }
        if (target != null) {
            val to = (target.key as? String)?.let(ids::indexOf) ?: -1
            if (to >= 0 && ((to > index && center > target.offset + target.size / 2) || (to < index && center < target.offset + target.size / 2))) move(id, to)
        }
        dragY = fingerY - row.offset - row.size / 2f
    }
    // Frame-clock edge scroll follows the finger while held still; no per-event job pile-up.
    LaunchedEffect(dragging) {
        if (dragging == null) return@LaunchedEffect
        var previous = 0L
        while (isActive) {
            var dragScroll = 0f
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
                // scrollBy is suspend, performed immediately below the frame callback.
                dragScroll = speed * 400f * density * dt
            }
            if (dragScroll != 0f) list.scrollBy(dragScroll)
            updateTarget()
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(enabled = !busy, onClick = onClose) { Text("Cancel") }
            Text("Reorder members", color = p.ink, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Button(enabled = !busy && dragging == null, onClick = {
                busy = true; error = null
                actions.launch {
                    try {
                        val latest = chorus.awaitModel()
                        require(MemberOrdering.eligible(latest.members, account).associate { it.id to MemberOrdering.key(it) } == baseline) {
                            "Members changed on another device. Cancel and reopen Reorder."
                        }
                        for ((id, key) in MemberOrdering.patches(latest.members, account, ids, moved.toSet()))
                            chorus.create("member.set", id, JSONObject().put("sort_key", key))
                        onClose()
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) { error = e.message ?: "Could not save order. Try again." }
                    finally { busy = false }
                }
            }) { Text(if (busy) "Saving…" else "Apply") }
        }
        Text("Drag a handle to move a member. Changes stay here until Apply.", color = p.ink3,
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        error?.let { Text(it, color = p.danger, modifier = Modifier.padding(16.dp)) }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), state = list,
            verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(ids, key = { it }) { id ->
                val member = latestModel.member(id)
                if (member != null) {
                    val active = dragging == id
                    Row(Modifier.fillMaxWidth().animateItem(placementSpec = if (motion && !active) tween(180) else null)
                        .zIndex(if (active) 1f else 0f).graphicsLayer { translationY = if (active) dragY else 0f }
                        .clip(RoundedCornerShape(14.dp)).background(if (active) p.surface3 else p.surface)
                        .padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(member.glyph, member.color, 40.dp, avatarBlob = member.avatarBlob)
                        Column(Modifier.weight(1f).padding(start = 12.dp, top = 10.dp, bottom = 10.dp)) {
                            Text(member.shownName, color = tonesOf(member.color).name, style = MaterialTheme.typography.titleMedium)
                            Row {
                                TextButton(enabled = !busy && dragging == null && ids.indexOf(id) > 0,
                                    onClick = { move(id, ids.indexOf(id) - 1) }) { Text("Earlier") }
                                TextButton(enabled = !busy && dragging == null && ids.indexOf(id) < ids.lastIndex,
                                    onClick = { move(id, ids.indexOf(id) + 1) }) { Text("Later") }
                            }
                        }
                        val ink = p.ink2
                        Canvas(Modifier.size(48.dp).semantics {
                            contentDescription = "Drag ${member.shownName}"
                            customActions = buildList {
                                if (!busy && dragging == null && ids.indexOf(id) > 0)
                                    add(CustomAccessibilityAction("Move earlier") { move(id, ids.indexOf(id) - 1); true })
                                if (!busy && dragging == null && ids.indexOf(id) < ids.lastIndex)
                                    add(CustomAccessibilityAction("Move later") { move(id, ids.indexOf(id) + 1); true })
                            }
                        }.pointerInput(id, busy) {
                            if (!busy) detectDragGestures(onDragStart = {
                                val row = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id }
                                if (row != null) {
                                    beforeDrag = latestIds; beforeMoved = moved; dragging = id
                                    fingerY = row.offset + row.size / 2f; dragY = 0f
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
}
