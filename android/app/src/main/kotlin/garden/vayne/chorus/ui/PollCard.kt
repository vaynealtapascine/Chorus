package garden.vayne.chorus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import garden.vayne.chorus.data.Chorus
import garden.vayne.chorus.data.Model
import garden.vayne.chorus.data.Poll
import garden.vayne.chorus.data.PollTally
import garden.vayne.chorus.data.Polls
import garden.vayne.chorus.designsystem.LocalChorusPalette
import garden.vayne.chorus.designsystem.R
import kotlinx.coroutines.launch

/**
 * A poll under a message or post (D-081). Totals are the server's counts-only tally; the acting
 * member's own choice shows at once. Votes go to this account's own scope.
 */
@Composable
fun PollCard(chorus: Chorus, model: Model, parentId: String, member: String?,
    definition: Poll? = model.polls.polls[parentId], remoteTally: PollTally? = null) {
    val poll = definition ?: return
    val p = LocalChorusPalette.current
    val actions = rememberCoroutineScope()
    val tally = model.polls.tallies[parentId] ?: remoteTally
    val mine = model.polls.choices(parentId, member)
    val most = (tally?.counts?.values?.maxOrNull() ?: 0L).coerceAtLeast(1L).toFloat()
    val bar = p.accent.copy(alpha = 0.16f)
    fun cast(choices: List<String>) {
        val who = member ?: return
        actions.launch { runCatching { chorus.create("poll.vote", parentId, Polls.votePayload(who, choices)) } }
    }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        poll.question?.let { Text(it, color = p.ink, fontWeight = FontWeight.SemiBold) }
        for (option in poll.options) {
            val n = tally?.counts?.get(option.id) ?: 0L
            val on = option.id in mine
            Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp))
                .border(1.dp, if (on) p.accent else p.line, RoundedCornerShape(8.dp))
                .background(p.surface)
                .drawBehind { drawRect(bar, size = Size(size.width * n / most, size.height)) }
                .clickable(enabled = member != null, role = Role.Checkbox) { cast(Polls.toggled(poll, mine, option.id)) }
                .semantics { selected = on; contentDescription = "${option.text}${if (tally != null) ", $n" else ""}" }) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    // a round mark for one choice, a square one for several: the shape says how to vote
                    val mark = if (poll.multiple) RoundedCornerShape(4.dp) else CircleShape
                    Box(Modifier.size(18.dp).clip(mark).background(if (on) p.accent else p.surface).border(1.5.dp, if (on) p.accent else p.ink3, mark),
                        contentAlignment = Alignment.Center) { if (on) Glyph(R.drawable.ic_check, p.onAccent, 12.dp) }
                    Text(option.text, color = p.ink, modifier = Modifier.weight(1f))
                    if (tally != null) Text(n.toString(), color = p.ink2, fontSize = 13.sp)
                }
            }
        }
        // tapping a chosen option again takes the vote back (Polls.toggled)
        tally?.let { Text("${it.voters} ${if (it.voters == 1L) "vote" else "votes"}", color = p.ink3, fontSize = 12.sp) }
    }
}

/** "Add a poll" fields for a composer's options: option texts and single/multiple choice. */
@Composable
fun PollDraftFields(on: Boolean, onToggle: (Boolean) -> Unit, options: SnapshotStateList<String>,
    multiple: Boolean, onMultiple: (Boolean) -> Unit) {
    val p = LocalChorusPalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(on, onToggle)
        Text("Add a poll", color = p.ink)
    }
    if (!on) return
    options.forEachIndexed { i, text ->
        ChorusField(text, { options[i] = it.take(200) }, label = { Text("Option ${i + 1}") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
    }
    if (options.size < Polls.MAX_OPTIONS) TextButton(onClick = { options.add("") }) { Text("Add option") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(multiple, onMultiple)
        Text("Allow more than one choice", color = p.ink)
    }
}
