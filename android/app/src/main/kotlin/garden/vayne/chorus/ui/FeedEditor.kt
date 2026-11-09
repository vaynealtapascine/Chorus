package garden.vayne.chorus.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import garden.vayne.chorus.data.Audience
import garden.vayne.chorus.data.Chorus
import garden.vayne.chorus.data.FeedInfo
import garden.vayne.chorus.data.FeedRules
import garden.vayne.chorus.data.Feeds
import garden.vayne.chorus.data.Model
import garden.vayne.chorus.data.ProfileAudience
import garden.vayne.chorus.designsystem.LocalChorusPalette
import garden.vayne.chorus.designsystem.R
import kotlinx.coroutines.launch
import org.json.JSONObject

private val RULE_LABEL = mapOf("from" to "From", "tag" to "Tagged", "kind" to "Kind", "mood" to "Mood", "since" to "Written in the last")
private val RULE_ICON = mapOf("from" to R.drawable.ic_users, "tag" to R.drawable.ic_hash, "kind" to R.drawable.ic_book,
    "mood" to R.drawable.ic_emoji, "since" to R.drawable.ic_clock)
private val SINCE = listOf("1d" to "Day", "7d" to "Week", "30d" to "Month", "1y" to "Year")

/**
 * F6.7: a feed as rows (who, which tags, what kind…) over the query string it is saved with, and a
 * live count of our own matching posts. A query the rows can't say is shown as text and kept as it is.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FeedEditor(chorus: Chorus, model: Model, feed: FeedInfo?, onClose: () -> Unit, onSaved: (String) -> Unit) {
    val p = LocalChorusPalette.current
    val actions = rememberCoroutineScope()
    var name by rememberSaveable(feed?.id) { mutableStateOf(feed?.name.orEmpty()) }
    var description by rememberSaveable(feed?.id) { mutableStateOf(feed?.description.orEmpty()) }
    var query by rememberSaveable(feed?.id) { mutableStateOf(feed?.query.orEmpty()) }
    var audienceJson by rememberSaveable(feed?.id) {
        mutableStateOf((model.savedFeeds.find { it.id == feed?.id }?.audience ?: Audience.PRIVATE).toJson().toString())
    }
    val audience = ProfileAudience.of(JSONObject(audienceJson))
    var adding by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val rules = FeedRules.parse(query)
    fun setRules(next: FeedRules) { query = next.query() }
    val now = System.currentTimeMillis()
    val matches = remember(rules, model.posts) { rules?.let { r -> model.posts.filter { it.occurredAt >= now - 7 * 86_400_000L && r.matches(it, model, now) } }.orEmpty() }
    BackHandler { onClose() }
    val canSave = !busy && name.isNotBlank() && query.isNotBlank()
    fun save() {
        busy = true; error = null
        actions.launch {
            try {
                val payload = Feeds.definition(name, description, query, audience, model.privacyBuckets.map { it.id }.toSet())
                val id = feed?.id ?: chorus.newId()
                chorus.create("feed.set", id, payload)
                onSaved(id); onClose()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Could not save this feed." }
            finally { busy = false }
        }
    }
    Column(Modifier.fillMaxSize().background(p.bg)) {
        Row(Modifier.fillMaxWidth().background(p.surface).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            ChatIconButton(R.drawable.ic_back, "Back", onClose)
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(if (feed == null) "New feed" else "Edit feed", color = p.ink, style = MaterialTheme.typography.titleMedium)
                if (feed != null) Text(feed.name, color = p.ink3, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
            if (feed != null) ChatIconButton(R.drawable.ic_delete, "Delete feed") { confirmDelete = true }
            ChatIconButton(R.drawable.ic_check, "Save feed") { if (canSave) save() }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(p.accentSoft), contentAlignment = Alignment.Center) {
                        Glyph(R.drawable.ic_sliders, p.accent, 26.dp)
                    }
                    ChorusField(name, { name = it.take(80) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.weight(1f))
                }
            }
            item { ChorusField(description, { description = it }, label = { Text("Description") }, modifier = Modifier.fillMaxWidth()) }
            if (rules == null) item {
                ChorusField(query, { query = it }, label = { Text("Filter") }, minLines = 2, modifier = Modifier.fillMaxWidth())
            } else {
                item { Text("Show posts that match all of these", color = p.accent, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
                for (rule in rules.rules) item(key = "rule:${rule.key}") {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(cardColor()).padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Glyph(RULE_ICON.getValue(rule.key), p.ink3, 22.dp)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(RULE_LABEL.getValue(rule.key), color = p.ink3, fontSize = 12.sp)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                for (value in rule.values) {
                                    val member = if (rule.key == "from") model.members.find { it.name.equals(value, true) || it.displayName.equals(value, true) } else null
                                    Row(Modifier.clip(CircleShape).background(if (rule.key == "tag") p.accentSoft else p.surface3)
                                        .clickable(role = Role.Button, onClickLabel = if (rule.key in setOf("kind", "since")) "Change" else "Remove") {
                                            if (rule.key in setOf("kind", "since")) choosing = rule.key else setRules(rules.with(rule.key, rule.values - value))
                                        }
                                        .padding(start = if (member != null) 4.dp else 12.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        member?.let { ChatAvatar(it, 22.dp) }
                                        Text(when (rule.key) { "tag" -> "#$value"; "kind" -> if (value == "entry") "Journal entries" else "Posts"
                                            "since" -> SINCE.find { it.first == value }?.second ?: value; else -> value },
                                            color = if (rule.key == "tag") p.accent else p.ink, fontSize = 14.sp)
                                    }
                                }
                                if (rule.key in setOf("from", "tag", "mood")) Box(Modifier.size(32.dp).clip(CircleShape).background(p.surface3)
                                    .clickable(role = Role.Button, onClickLabel = "Add") { adding = rule.key }, contentAlignment = Alignment.Center) {
                                    Glyph(R.drawable.ic_plus_circle, p.ink2, 18.dp)
                                }
                            }
                        }
                        BarIcon(R.drawable.ic_close, "Remove this rule", p.ink3, 20.dp) { setRules(rules.without(rule.key)) }
                    }
                }
                item {
                    val free = FeedRules.ORDER.filter { rules.values(it).isEmpty() }
                    if (free.isNotEmpty()) Row(Modifier.clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button) { picking = true }.padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Glyph(R.drawable.ic_plus_circle, p.accent, 20.dp)
                        Text("Add rule", color = p.accent, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth().clickable(role = Role.Switch) { setRules(rules.copy(replies = !rules.replies)) }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("Include replies", color = p.ink, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        ChorusSwitch(rules.replies, { setRules(rules.copy(replies = it)) })
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Who can open it", color = p.ink2, modifier = Modifier.weight(1f))
                    AudienceChip("This feed", audience, own = true, buckets = model.sharingBuckets, person = model.isPerson,
                        onChange = { audienceJson = (it ?: Audience.PRIVATE).toJson().toString() }, looks = model.bucketLooks)
                }
            }
            if (audience.mode != "private" && Feeds.usesFronting(query)) item {
                Text("Shows who was fronting, as far as readers were told", color = p.ink3, fontSize = 13.sp)
            }
            if (rules != null) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Live preview", color = p.accent, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        Text("${matches.size} ${if (matches.size == 1) "post" else "posts"} this week", color = p.ink3, fontSize = 13.sp)
                    }
                }
                items(matches.sortedByDescending { it.occurredAt }.take(3), key = { "m:${it.id}" }) { post ->
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(1.dp, p.line, RoundedCornerShape(16.dp))) {
                        JournalPostCard(post, model, chorus, onReply = {}, showActions = false, divider = false)
                    }
                }
            }
            if (error != null) item { Text(error.orEmpty(), color = p.danger) }
        }
        Column(Modifier.fillMaxWidth().background(p.surface).padding(16.dp)) { DrawerButton("Save feed", { save() }, enabled = canSave, busy = busy) }
    }
    if (picking && rules != null) MenuDrawer(true, { picking = false }, "Add rule") {
        for (key in FeedRules.ORDER.filter { rules.values(it).isEmpty() }) MenuRow(RULE_LABEL.getValue(key), RULE_ICON[key]) {
            picking = false
            when (key) {
                "kind" -> setRules(rules.with("kind", listOf("note")))
                "since" -> setRules(rules.with("since", listOf("7d")))
                else -> adding = key
            }
        }
    }
    val addKey = adding
    if (addKey != null && rules != null) {
        if (addKey == "from") {
            var chosen by remember { mutableStateOf(rules.values("from").toSet()) }
            Drawer({ adding = null }, "From", footer = { DrawerButton("Done", { setRules(rules.with("from", chosen.toList())); adding = null }) }) {
                for (m in model.active) MenuRow(m.shownName, selected = m.name in chosen, leading = { ChatAvatar(m, 36.dp) }) {
                    chosen = if (m.name in chosen) chosen - m.name else chosen + m.name
                }
            }
        } else {
            var text by remember { mutableStateOf("") }
            fun add() {
                val v = text.trim().trimStart('#').lowercase().takeIf { it.isNotEmpty() && it !in rules.values(addKey) }
                if (v != null) setRules(rules.with(addKey, rules.values(addKey) + v))
                adding = null
            }
            Drawer({ adding = null }, RULE_LABEL.getValue(addKey), footer = { DrawerButton("Add", { add() }, enabled = text.isNotBlank()) }) {
                ChorusField(text, { text = it }, singleLine = true, label = { Text(if (addKey == "tag") "Tag" else "Mood") }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
    // kind and since are single choices
    if (choosing != null && rules != null) {
        val key = choosing!!
        val options = if (key == "kind") listOf("note" to "Posts", "entry" to "Journal entries") else SINCE
        MenuDrawer(true, { choosing = null }, RULE_LABEL.getValue(key)) {
            for ((value, label) in options) MenuRow(label, selected = rules.values(key).firstOrNull() == value) { setRules(rules.with(key, listOf(value))); choosing = null }
        }
    }
    if (confirmDelete && feed != null) ConfirmDrawer("Delete this feed?", null, "Delete", {
        confirmDelete = false
        actions.launch {
            try { chorus.create("feed.delete", feed.id, JSONObject()); onClose() }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Could not delete this feed." }
        }
    }, { confirmDelete = false }, danger = true)
}
