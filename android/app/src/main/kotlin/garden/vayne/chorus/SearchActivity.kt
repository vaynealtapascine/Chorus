package garden.vayne.chorus

import android.os.Bundle
import android.widget.Toast
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
import garden.vayne.chorus.data.Chorus
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
import kotlinx.coroutines.launch

/**
 * The search launcher (CLIENTS.md §3.4): opens over the home screen with the keyboard up; type a
 * few letters, tap (or press Enter for the top match) and it switches and closes.
 */
class SearchActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val chorus = Chorus.get(this)
        setContent { ChorusTheme { Launcher(chorus, ::finish) } }
    }
}

/** Members and subsystems matching `query`, best first. */
fun searchSubjects(model: Model, query: String): List<Subject> {
    val members = model.active.map { m ->
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

@Composable
private fun Launcher(chorus: Chorus, close: () -> Unit) {
    val p = LocalChorusPalette.current
    val model by chorus.model.collectAsState()
    var query by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val results = remember(model, query) { searchSubjects(model, query) }

    if (model.isPerson) {
        Box(Modifier.fillMaxSize().background(Color(0x66000000)).clickable(onClick = close).statusBarsPadding().padding(16.dp),
            contentAlignment = Alignment.TopCenter) {
            Text("Quick switching is for systems", color = p.ink,
                modifier = Modifier.fillMaxWidth().background(p.bg, RoundedCornerShape(24.dp)).padding(20.dp))
        }
        return
    }

    fun pick(s: Subject) {
        scope.launch {
            try {
                val label = Front.tap(chorus, s, Mode.Replace)
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
                    keyboardActions = KeyboardActions(onGo = { results.firstOrNull()?.let(::pick) }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
            if (results.isEmpty()) Text(if (query.isBlank()) "No members yet." else "No one matches “$query”.", color = p.ink3, modifier = Modifier.padding(12.dp))
            LazyColumn {
                items(results, key = { "${it.type}:${it.id}" }) { s -> ResultRow(s) { pick(s) } }
            }
        }
    }
}

@Composable
private fun ResultRow(s: Subject, onTap: () -> Unit) {
    val p = LocalChorusPalette.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onTap).padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(s.glyph, s.color, 36.dp, avatarBlob = s.avatarBlob)
        Spacer(Modifier.width(12.dp))
        Text(s.name, color = tonesOf(s.color).name, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        if (s.type == "group") Text("whole subsystem", color = p.ink3, fontSize = 12.sp)
    }
}
