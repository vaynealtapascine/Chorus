package garden.vayne.chorus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import garden.vayne.chorus.data.Chorus
import garden.vayne.chorus.data.Front
import garden.vayne.chorus.data.FrontReview
import garden.vayne.chorus.data.FrontReviews
import garden.vayne.chorus.data.FrontScene
import garden.vayne.chorus.data.Model
import garden.vayne.chorus.data.SwitchRow
import garden.vayne.chorus.designsystem.LocalChorusPalette
import garden.vayne.chorus.designsystem.R
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

private enum class WhatHappened { Both, A, B }

/** Two devices switched at nearly the same time (F3.6): what each one recorded, and what really happened. */
@Composable
internal fun FrontReviewPage(chorus: Chorus, model: Model, r: FrontReview, left: Int, saved: List<SwitchRow>, frontId: String?, onLater: () -> Unit) {
    val p = LocalChorusPalette.current
    val actions = rememberCoroutineScope()
    val a = saved.firstOrNull { it.id == r.switchA }
    val b = saved.firstOrNull { it.id == r.switchB }
    var pick by remember(r.id) { mutableStateOf(WhatHappened.Both) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val me = chorus.device?.deviceId
    val time = DateFormat.getTimeInstance(DateFormat.SHORT)
    val layers = FrontScene.layers(model)
    fun device(s: SwitchRow?) = s?.deviceId?.let { model.deviceNames[it] ?: if (it == me) "This device" else null } ?: "Another device"
    fun who(s: SwitchRow?) = s?.resultingFront?.joinToString(" & ") { model.subject(it.subjectType, it.subjectId)?.name ?: "Someone" }?.ifEmpty { "No one" } ?: "Someone"
    fun layer(s: SwitchRow?) = s?.resultingFront?.firstOrNull()?.let { e -> layers.firstOrNull { it.id == e.level }?.name?.lowercase() }
    fun story(s: SwitchRow?) = listOfNotNull(who(s), layer(s), s?.let { "from ${time.format(Date(it.occurredAt))}" }).joinToString(" ")

    suspend fun resolve(resolution: String) = chorus.create("front.review_resolve", null, JSONObject().put("review_id", r.id).put("resolution", resolution))
    suspend fun retract(id: String) {
        if (frontId != null) chorus.create("front.change", frontId, JSONObject().put("action", "retract").put("target_op_id", id))
        else chorus.create("front.retract", chorus.newId(), JSONObject().put("target_op_id", id))
    }
    suspend fun together() {
        if (a == null || b == null || a.retracted || b.retracted) return resolve("keep_both").let { }
        val seen = HashSet<String>()
        val entries = (a.resultingFront + b.resultingFront).filter { seen.add("${it.subjectType}:${it.subjectId}") }
        if (frontId != null) {
            // history only, so following and any override stay as they were (D-083)
            chorus.create("front.change", frontId, Front.changePayload(entries, at = maxOf(a.occurredAt, b.occurredAt), historyOnly = true))
            retract(a.id); retract(b.id)
        } else chorus.create("front.switch", chorus.newId(), Front.switchPayload(entries, notify = "silent"), userTime = maxOf(a.occurredAt, b.occurredAt))
        resolve("merge")
    }
    fun save() {
        if (busy) return
        busy = true
        actions.launch {
            try {
                when (pick) {
                    WhatHappened.Both -> together()
                    WhatHappened.A -> { retract(r.switchB); resolve("keep_a") }
                    WhatHappened.B -> { retract(r.switchA); resolve("keep_b") }
                }
                error = null
            } catch (e: Exception) { error = e.message ?: "Couldn’t save. Try again." } finally { busy = false }
        }
    }

    Column(Modifier.fillMaxSize().background(p.bg)) {
        SubpageBar("Review", "$left ${if (left == 1) "thing" else "things"} to check", onLater)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth().clip16(p.surface).padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(40.dp).background(p.warn.copy(alpha = .16f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { Glyph(R.drawable.ic_alert, p.warn, 20.dp) }
                Text("Two devices switched within a minute", color = p.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (s in listOf(a, b)) Column(Modifier.weight(1f).clip16(p.surface).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(listOfNotNull(device(s), s?.let { time.format(Date(it.occurredAt)) }).joinToString(" · "), color = p.ink3, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        s?.resultingFront?.firstOrNull()?.let { model.subject(it.subjectType, it.subjectId) }?.let { Avatar(it.glyph, it.color, 36.dp, avatarBlob = it.avatarBlob, ring = false) }
                        Column {
                            Text(who(s), color = p.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            layer(s)?.let { Text(it, color = p.ink3, fontSize = 12.sp, maxLines = 1) }
                        }
                    }
                }
            }
            SectionLabel("What happened?")
            FormCard {
                Choice("Both are right", FrontReviews.together(who(a), who(b)), pick == WhatHappened.Both) { pick = WhatHappened.Both }
                FormDivider()
                Choice(FrontReviews.keepLabel(device(a)), story(a), pick == WhatHappened.A, enabled = a != null && b != null) { pick = WhatHappened.A }
                FormDivider()
                Choice(FrontReviews.keepLabel(device(b)), story(b), pick == WhatHappened.B, enabled = a != null && b != null) { pick = WhatHappened.B }
            }
            error?.let { Text(it, color = p.danger, fontSize = 14.sp) }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DrawerButton("Later", onLater, ButtonKind.Neutral, modifier = Modifier.weight(.3f))
            DrawerButton("Save to history", ::save, busy = busy, modifier = Modifier.weight(.7f))
        }
    }
}

private fun Modifier.clip16(color: androidx.compose.ui.graphics.Color) = background(color, RoundedCornerShape(16.dp))

@Composable
private fun Choice(title: String, subtitle: String, on: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val p = LocalChorusPalette.current
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(24.dp).border(2.dp, if (on) p.accent else p.ink3, CircleShape), contentAlignment = Alignment.Center) {
            if (on) Box(Modifier.size(12.dp).background(p.accent, CircleShape))
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = if (enabled) p.ink else p.ink3, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = p.ink3, fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
}
