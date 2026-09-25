package garden.vayne.chorus

import android.os.Bundle
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import garden.vayne.chorus.data.Chorus
import garden.vayne.chorus.data.Entry
import garden.vayne.chorus.data.Front
import garden.vayne.chorus.data.Mode
import garden.vayne.chorus.data.Model
import garden.vayne.chorus.data.Subject
import garden.vayne.chorus.designsystem.ChorusTheme
import garden.vayne.chorus.designsystem.LocalChorusPalette
import garden.vayne.chorus.ui.Avatar
import garden.vayne.chorus.ui.fuzzy
import garden.vayne.chorus.ui.tonesOf
import garden.vayne.chorus.widget.QuickSwitchWidget
import garden.vayne.chorus.widget.PinnedShortcuts
import kotlinx.coroutines.launch

/**
 * The search launcher (CLIENTS.md §3.4): opens over the home screen with the keyboard up; type a
 * few letters, tap (or press Enter for the top match) and it switches and closes.
 */
class SearchActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val chorus = Chorus.get(this)
        if (intent.action == "garden.vayne.chorus.SWITCH_OUT") {
            if (savedInstanceState != null) { finish(); return }
            lifecycleScope.launch {
                try {
                    val model = chorus.awaitModel()
                    if (chorus.device == null || model.isPerson) throw IllegalStateException("Switching is for systems.")
                    Front.switch(chorus, emptyList(), "Switched out")
                    QuickSwitchWidget.refreshAll(this@SearchActivity)
                    Toast.makeText(this@SearchActivity, "Switched out", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this@SearchActivity, e.message ?: "Couldn't switch out", Toast.LENGTH_LONG).show()
                } finally { finish() }
            }
            return
        }
        val pinnedId = intent.getStringExtra(PinnedShortcuts.EXTRA_MEMBER_ID)
        if (pinnedId != null) {
            val pinnedAccount = intent.getStringExtra(PinnedShortcuts.EXTRA_ACCOUNT_ID)
            lifecycleScope.launch {
                try {
                    val model = chorus.awaitModel()
                    val member = model.active.find { it.id == pinnedId &&
                        (it.createdByAccountId == null || it.createdByAccountId == pinnedAccount) }
                    if (chorus.device?.accountId != pinnedAccount || member == null || model.isPerson) {
                        Toast.makeText(this@SearchActivity, "This shortcut is no longer available.", Toast.LENGTH_SHORT).show()
                    } else {
                        val subject = Subject("member", member.id, member.shownName, member.color, member.glyph, member.avatarBlob)
                        val label = Front.tap(chorus, subject, Mode.Replace)
                        QuickSwitchWidget.refreshAll(this@SearchActivity)
                        Toast.makeText(this@SearchActivity, label, Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(this@SearchActivity, e.message ?: "Couldn't switch", Toast.LENGTH_LONG).show()
                } finally { finish() }
            }
            return
        }
        setContent { ChorusTheme { Launcher(chorus, ::finish) } }
    }
}

/** Members and subsystems matching `query`, best first. */
fun searchSubjects(model: Model, query: String, accountId: String?): List<Subject> {
    val members = model.active.filter { it.createdByAccountId == null || it.createdByAccountId == accountId }.map { m ->
        Subject("member", m.id, m.shownName, m.color, m.glyph, m.avatarBlob) to listOfNotNull(m.name, m.displayName, m.pronouns, *m.sigils.toTypedArray()).joinToString(" ")
    }
    val groups = model.groups.filter { it.isSubsystem }.map { g ->
        Subject("group", g.id, g.name, g.color ?: "#A09184", "◌") to g.name
    }
    if (query.isBlank()) {
        val recent = model.recents(8).mapNotNull { id -> members.firstOrNull { it.first.id == id }?.first }
        return recent.ifEmpty { members.take(8).map { it.first } }
    }
    return (members + groups).mapNotNull { (s, text) -> fuzzy(query, text)?.let { score -> s to score + if (s.type == "group") 1 else 0 } }
        .sortedBy { it.second }.map { it.first }.take(30)
}

/** Keep the user's selection order; only subjects this account can switch to enter the op. */
fun multiSwitchEntries(model: Model, selected: List<Subject>, accountId: String?): List<Entry> {
    val ownMembers = model.active.filter { it.createdByAccountId == null || it.createdByAccountId == accountId }
        .map { it.id }.toSet()
    val subsystems = model.groups.filter { it.isSubsystem }.map { it.id }.toSet()
    val seen = HashSet<Pair<String, String>>()
    return selected.filter { subject ->
        seen.add(subject.type to subject.id) && when (subject.type) {
            "member" -> subject.id in ownMembers
            "group" -> subject.id in subsystems
            else -> false
        }
    }.mapIndexed { index, subject -> Entry(subject.type, subject.id, "front", index == 0) }
}

@Composable
private fun Launcher(chorus: Chorus, close: () -> Unit) {
    val p = LocalChorusPalette.current
    val model by chorus.model.collectAsState()
    var query by remember { mutableStateOf("") }
    var multi by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<Subject>() }
    val focus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val accountId = chorus.device?.accountId
    val results = remember(model, query, accountId) { searchSubjects(model, query, accountId) }
    LaunchedEffect(accountId) { selected.clear() }
    LaunchedEffect(model) {
        val valid = multiSwitchEntries(model, selected, accountId).map { it.subjectType to it.subjectId }.toSet()
        selected.removeAll { (it.type to it.id) !in valid }
    }

    if (model.isPerson) {
        Box(Modifier.fillMaxSize().background(Color(0x66000000)).clickable(onClick = close).statusBarsPadding().padding(16.dp),
            contentAlignment = Alignment.TopCenter) {
            Text("Quick switching is for systems", color = p.ink,
                modifier = Modifier.fillMaxWidth().background(p.bg, RoundedCornerShape(24.dp)).padding(20.dp))
        }
        return
    }

    fun pick(s: Subject, mode: Mode) {
        scope.launch {
            try {
                val label = Front.tap(chorus, s, mode)
                QuickSwitchWidget.refreshAll(ctx)
                Toast.makeText(ctx, label, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(ctx, e.message ?: "Couldn't switch", Toast.LENGTH_LONG).show()
            }
            close()
        }
    }
    fun switchSelected() {
        val entries = multiSwitchEntries(model, selected, accountId)
        if (entries.isEmpty()) return
        scope.launch {
            try {
                val names = selected.filter { s -> entries.any { it.subjectType == s.type && it.subjectId == s.id } }
                    .joinToString(" + ") { it.name }
                val label = "Switched to $names"
                Front.switch(chorus, entries, label)
                QuickSwitchWidget.refreshAll(ctx)
                Toast.makeText(ctx, label, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(ctx, e.message ?: "Couldn't switch", Toast.LENGTH_LONG).show()
            }
            close()
        }
    }

    LaunchedEffect(Unit) { focus.requestFocus() }
    Box(
        Modifier.fillMaxSize().background(Color(0x66000000))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = close)
            .statusBarsPadding().imePadding().padding(16.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 520.dp).background(p.bg, RoundedCornerShape(24.dp))
                .border(1.dp, p.line, RoundedCornerShape(24.dp))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.fillMaxWidth().background(p.surface, RoundedCornerShape(999.dp)).padding(horizontal = 16.dp, vertical = 12.dp)) {
                if (query.isEmpty()) Text("Switch to…", color = p.ink3, fontSize = 16.sp)
                BasicTextField(
                    query, { query = it }, singleLine = true,
                    textStyle = TextStyle(color = p.ink, fontSize = 16.sp),
                    cursorBrush = SolidColor(p.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = {
                        if (multi) switchSelected() else results.firstOrNull()?.let { pick(it, Mode.Replace) }
                    }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text(if (multi) "Multi ✓" else "Multi", color = if (multi) p.accent else p.ink2,
                    modifier = Modifier.clickable { multi = !multi; selected.clear() }.padding(8.dp)
                        .semantics { contentDescription = "Multi switch" })
                if (multi) Text("Switch (${selected.size})", color = if (selected.isNotEmpty()) p.accent else p.ink3,
                    modifier = Modifier.clickable(enabled = selected.isNotEmpty()) { switchSelected() }.padding(8.dp)
                        .semantics { contentDescription = "Switch selected members" })
            }
            if (results.isEmpty()) Text(if (query.isBlank()) "No members yet." else "No one matches “$query”.", color = p.ink3, modifier = Modifier.padding(12.dp))
            LazyColumn {
                items(results, key = { "${it.type}:${it.id}" }) { s ->
                    ResultRow(s, multi, selected.any { it.type == s.type && it.id == s.id },
                        onTap = {
                            if (multi) {
                                val index = selected.indexOfFirst { it.type == s.type && it.id == s.id }
                                if (index >= 0) selected.removeAt(index) else selected.add(s)
                            } else pick(s, Mode.Replace)
                        },
                        onAdd = { pick(s, Mode.Add) }, onRemove = { pick(s, Mode.Remove) })
                }
            }
        }
    }
}

@Composable
private fun ResultRow(s: Subject, multi: Boolean, selected: Boolean, onTap: () -> Unit,
    onAdd: () -> Unit, onRemove: () -> Unit) {
    val p = LocalChorusPalette.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onTap).padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(s.glyph, s.color, 36.dp, avatarBlob = s.avatarBlob)
        Spacer(Modifier.width(12.dp))
        Text(s.name, color = tonesOf(s.color).name, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        if (multi) Text(if (selected) "✓" else "+", color = p.accent, fontSize = 18.sp,
            modifier = Modifier.padding(horizontal = 10.dp))
        else {
            Text("Add", color = p.accent, fontSize = 12.sp,
                modifier = Modifier.clickable(onClick = onAdd).padding(horizontal = 6.dp, vertical = 8.dp)
                    .semantics { contentDescription = "Add ${s.name} to front" })
            Text("Remove", color = p.accent, fontSize = 12.sp,
                modifier = Modifier.clickable(onClick = onRemove).padding(horizontal = 6.dp, vertical = 8.dp)
                    .semantics { contentDescription = "Remove ${s.name} from front" })
        }
    }
}
