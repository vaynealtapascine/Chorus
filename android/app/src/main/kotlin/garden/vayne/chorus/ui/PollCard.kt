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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.style.TextAlign
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
 * member's own choice shows at once. Votes go to this account's own scope. Results appear once the
 * voter has chosen (F6.9/F6.10): before that the options are plain buttons. [internal] polls (our
 * system's own posts) say how many of our members voted and let the voter be changed.
 */
@Composable
fun PollCard(chorus: Chorus, model: Model, parentId: String, member: String?,
    definition: Poll? = model.polls.polls[parentId], remoteTally: PollTally? = null, internal: Boolean = false) {
    val poll = definition ?: return
    val p = LocalChorusPalette.current
    val actions = rememberCoroutineScope()
    val tally = model.polls.tallies[parentId] ?: remoteTally
    val account = chorus.device?.accountId
    val ours = model.active.filter { it.createdByAccountId == null || it.createdByAccountId == account }
    // who is voting: the acting member, unless the voter picks another of ours
    var picked by rememberSaveable(parentId) { mutableStateOf<String?>(null) }
    val voter = (if (internal) picked?.takeIf { id -> ours.any { it.id == id } } else null) ?: member
    var changing by remember { mutableStateOf(false) }
    val mine = model.polls.choices(parentId, voter)
    val voters = (tally?.voters ?: 0L).coerceAtLeast(1L)
    val showResults = tally != null && (mine.isNotEmpty() || voter == null)
    val bar = p.accent.copy(alpha = 0.16f)
    val shape = RoundedCornerShape(12.dp)
    fun cast(choices: List<String>) {
        val who = voter ?: return
        actions.launch { runCatching { chorus.create("poll.vote", parentId, Polls.votePayload(who, choices)) } }
    }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        poll.question?.let { Text(it, color = p.ink, fontWeight = FontWeight.SemiBold) }
        if (internal && ours.size > 1) ours.find { it.id == voter }?.let { who ->
            Row(Modifier.clip(CircleShape).background(p.surface2).clickable(role = Role.Button) { changing = true }
                .padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChatAvatar(who, 24.dp)
                Text("Voting as ${who.shownName}", color = p.ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text("Change", color = p.accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
            MenuDrawer(changing, { changing = false }, "Vote as") {
                for (m in ours) MenuRow(m.shownName, selected = m.id == who.id, leading = { ChatAvatar(m, 36.dp) }) { picked = m.id; changing = false }
            }
        }
        for (option in poll.options) {
            val n = tally?.counts?.get(option.id) ?: 0L
            val on = option.id in mine
            val percent = Polls.percent(n, voters)
            val rowShape = if (showResults) shape else shape
            Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(rowShape)
                .border(1.dp, if (showResults) (if (on) p.accent else p.line) else p.accent.copy(alpha = .6f), rowShape)
                .background(p.surface)
                .drawBehind { if (showResults) drawRect(bar, size = Size(size.width * n / voters, size.height)) }
                .clickable(enabled = voter != null, role = Role.Checkbox) { cast(Polls.toggled(poll, mine, option.id)) }
                .semantics { selected = on; contentDescription = "${option.text}${if (showResults) ", $percent%" else ""}" },
                contentAlignment = Alignment.CenterStart) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (showResults && on) Glyph(R.drawable.ic_check, p.accent, 18.dp)
                    Text(option.text, color = if (showResults) p.ink else p.accent, fontWeight = FontWeight.Medium,
                        textAlign = if (showResults) TextAlign.Start else TextAlign.Center,
                        modifier = Modifier.weight(1f))
                    if (showResults) Text("$percent%", color = p.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
        // tapping a chosen option again takes the vote back (Polls.toggled)
        val line = if (internal && ours.size > 1) "${Polls.voted(model.polls, parentId, ours)} of ${ours.size} voted"
            else tally?.let { "${it.voters} ${if (it.voters == 1L) "vote" else "votes"}" }
        line?.let { Text(it, color = p.ink3, fontSize = 12.sp) }
    }
}

/** F6.10's Votes list: each of our members and what they chose, or that they haven't voted. */
@Composable
internal fun PollVotes(model: Model, chorus: Chorus, parentId: String, poll: Poll, voter: String?) {
    val p = LocalChorusPalette.current
    val account = chorus.device?.accountId
    val ours = model.active.filter { it.createdByAccountId == null || it.createdByAccountId == account }
    if (ours.size < 2) return
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text("Votes", color = p.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        for ((member, choices) in Polls.votesOf(model.polls, parentId, ours)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                ChatAvatar(member, 40.dp)
                Column(Modifier.weight(1f)) {
                    Text(member.shownName, color = p.ink, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Text(if (choices.isEmpty()) "Hasn’t voted yet" else choices.mapNotNull { id -> poll.options.find { it.id == id }?.text }.joinToString(", "),
                        color = p.ink3, fontSize = 13.sp)
                }
                if (member.id == voter) Text("You", color = p.accent, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clip(CircleShape).background(p.accentSoft).padding(horizontal = 12.dp, vertical = 5.dp))
            }
        }
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
