package garden.vayne.chorus.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import garden.vayne.chorus.data.Chorus
import garden.vayne.chorus.data.Model
import garden.vayne.chorus.data.SyncIssue
import garden.vayne.chorus.data.SyncIssues
import garden.vayne.chorus.designsystem.LocalChorusPalette
import garden.vayne.chorus.designsystem.R
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** F10.4 Sync issues: each change the server refused, with its text kept; Retry, Copy text, Discard; then what was fixed. */
@Composable
internal fun SyncIssuesScreen(chorus: Chorus, model: Model, onBack: () -> Unit) {
    val p = LocalChorusPalette.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val issues by chorus.syncIssues.collectAsState()
    val fixedRows by chorus.fixedIssues.collectAsState()
    val busy = remember { mutableStateMapOf<String, Boolean>() }
    var error by remember { mutableStateOf<String?>(null) }
    // the Fixed list asks the replica where each retry stands; it changes as ops are confirmed
    val now = remember(model, issues) { System.currentTimeMillis() }
    val fixed by produceState(emptyList<Pair<SyncIssues.Fixed, Boolean>>(), fixedRows, model, issues) {
        val status = fixedRows.associate { it.opId to chorus.opStatus(it.opId) }
        value = SyncIssues.shownFixed(fixedRows, now) { status[it] ?: "unknown" }
    }
    Column(Modifier.fillMaxSize().background(p.bg)) {
        SubpageBar("Sync issues", when (issues.size) {
            0 -> null
            1 -> "1 change couldn’t be saved"
            else -> "${issues.size} changes couldn’t be saved"
        }, onBack)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (issues.isNotEmpty()) item(key = "note") {
                FormNote("Nothing is lost. Your original text is still saved on this device.", R.drawable.ic_shield)
            }
            if (issues.isEmpty() && fixed.isEmpty()) item(key = "empty") {
                EmptyState(R.drawable.ic_check, "Everything is saved", Modifier.padding(top = 32.dp))
            }
            items(issues, key = { it.id }) { issue ->
                val title = SyncIssues.title(issue, model)
                IssueCard(issue, title, model, busy[issue.id] == true,
                    onRetry = {
                        busy[issue.id] = true; error = null
                        scope.launch {
                            try { chorus.retryIssue(issue, title) }
                            catch (e: Exception) { error = e.message ?: "Could not send it again." }
                            finally { busy.remove(issue.id) }
                        }
                    },
                    onCopy = {
                        ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Chorus text", SyncIssues.text(issue)))
                    },
                    onDiscard = {
                        busy[issue.id] = true; error = null
                        scope.launch {
                            try { chorus.dismissIssue(issue.id) }
                            catch (e: Exception) { error = e.message ?: "Could not discard this." }
                            finally { busy.remove(issue.id) }
                        }
                    })
            }
            error?.let { e -> item(key = "error") { Text(e, color = p.danger, fontSize = 14.sp) } }
            if (fixed.isNotEmpty()) {
                item(key = "fixed") { SectionLabel("Fixed") }
                items(fixed, key = { "fixed:${it.first.opId}" }) { (row, saved) -> FixedRow(row, saved, now) }
            }
        }
    }
}

@Composable
private fun IssueCard(issue: SyncIssue, title: String, model: Model, busy: Boolean, onRetry: () -> Unit, onCopy: () -> Unit, onDiscard: () -> Unit) {
    val p = LocalChorusPalette.current
    val text = SyncIssues.text(issue)
    FormCard {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(p.danger.copy(alpha = .16f)), contentAlignment = Alignment.Center) {
                    Glyph(R.drawable.ic_alert, p.danger, 20.dp)
                }
                Column(Modifier.weight(1f)) {
                    Text(title, color = p.ink, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 2,
                        overflow = TextOverflow.Ellipsis)
                    Text(SyncIssues.reason(issue), color = p.danger, fontSize = 13.sp, lineHeight = 17.sp)
                }
            }
            if (text.isNotEmpty()) {
                val author = SyncIssues.author(issue)?.let { model.member(it) }
                val bar = author?.let { tonesOf(it.color).name } ?: p.accent
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(p.surface2).height(IntrinsicSize.Min)) {
                    Box(Modifier.padding(vertical = 10.dp).padding(start = 10.dp).width(3.dp).fillMaxHeight().background(bar, RoundedCornerShape(2.dp)))
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        val time = if (issue.at > 0) DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(issue.at)) else null
                        listOfNotNull(author?.shownName, time).joinToString(" · ").takeIf { it.isNotEmpty() }?.let {
                            Text(it, color = bar, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Text(text, color = p.ink, fontSize = 15.sp, lineHeight = 21.sp, maxLines = 8, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("Retry", R.drawable.ic_restore, !busy, onRetry)
                if (text.isNotEmpty()) PillButton("Copy text", R.drawable.ic_copy, true, onCopy)
                Spacer(Modifier.weight(1f))
                PillButton("Discard", null, !busy, onDiscard, danger = true)
            }
        }
    }
}

/** F10.4's small rounded buttons under an issue (Retry, Copy text, Discard). */
@Composable
private fun PillButton(label: String, icon: Int?, enabled: Boolean, onClick: () -> Unit, danger: Boolean = false) {
    val p = LocalChorusPalette.current
    val ink = when { !enabled -> p.ink3; danger -> p.danger; else -> p.ink }
    Row(Modifier.heightIn(min = 36.dp).clip(RoundedCornerShape(18.dp)).background(if (danger) Color.Transparent else p.surface2)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        icon?.let { Glyph(it, ink, 16.dp) }
        Text(label, color = ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun FixedRow(row: SyncIssues.Fixed, saved: Boolean, now: Long) {
    val p = LocalChorusPalette.current
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(p.accentSoft), contentAlignment = Alignment.Center) {
            Glyph(R.drawable.ic_check, p.accent, 20.dp)
        }
        Column(Modifier.weight(1f)) {
            Text(row.title, color = p.ink2, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val a = ago(row.at, now)
            Text("${if (saved) "Saved on retry" else "Sending again"} · ${if (a == "just now") a else "$a ago"}", color = p.ink3, fontSize = 13.sp)
        }
    }
}
