package garden.vayne.chorus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import garden.vayne.chorus.data.ManyMembers
import garden.vayne.chorus.data.Member
import garden.vayne.chorus.designsystem.LocalChorusPalette
import garden.vayne.chorus.designsystem.R

/**
 * D-142: hold an action to do it as several of our members at once. Each of [members] has a round
 * check, ticked at first for those who [had] it; Everyone · Fronting · Nobody pick quickly
 * ([here]: who fronts now). Save hands [onApply] what changed, in the list's order. [lines] puts a
 * quiet second line under a name (a poll voter's other choice); [extra] sits under the list.
 */
@Composable
internal fun ManyMembersDrawer(title: String, subtitle: String?, members: List<Member>, had: Set<String>, here: Set<String>,
    onDismiss: () -> Unit, onApply: (ManyMembers.Change) -> Unit, lines: (Member) -> String? = { null },
    leading: (@Composable () -> Unit)? = null, extra: (@Composable ColumnScope.() -> Unit)? = null) {
    val p = LocalChorusPalette.current
    val order = remember(members) { members.map { it.id } }
    var chosen by remember(had) { mutableStateOf(had.filterTo(HashSet()) { it in order }.toSet()) }
    val change = ManyMembers.change(order, had, chosen)
    val fronting = order.filter { it in here }.toSet()
    Drawer(onDismiss, title, subtitle, leading, footer = {
        DrawerButton(if (change.empty) "Save" else "Save · ${change.add.size + change.remove.size} ${if (change.add.size + change.remove.size == 1) "change" else "changes"}",
            { onApply(change) }, enabled = !change.empty)
    }) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QuickPick("Everyone", chosen.size == order.size) { chosen = order.toSet() }
            if (fronting.isNotEmpty() && fronting.size < order.size) QuickPick("Fronting", chosen == fronting) { chosen = fronting }
            QuickPick("Nobody", chosen.isEmpty()) { chosen = emptySet() }
        }
        FormCard {
            members.forEachIndexed { i, m ->
                if (i > 0) FormDivider()
                val on = m.id in chosen
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    .toggleable(on, role = Role.Checkbox) { chosen = if (it) chosen + m.id else chosen - m.id }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    ChatAvatar(m, 36.dp)
                    Column(Modifier.weight(1f)) {
                        Text(m.shownName, color = p.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val line = lines(m) ?: if (m.id in here) "Fronting" else null
                        line?.let { Text(it, color = p.ink3, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                    Box(Modifier.size(26.dp).then(if (on) Modifier.background(p.accent, CircleShape) else Modifier.border(1.5.dp, p.ink3, CircleShape)),
                        contentAlignment = Alignment.Center) { if (on) Glyph(R.drawable.ic_check, p.onAccent, 16.dp) }
                }
            }
        }
        extra?.invoke(this)
    }
}

@Composable
private fun QuickPick(text: String, active: Boolean, onClick: () -> Unit) {
    val p = LocalChorusPalette.current
    Text(text, color = if (active) p.accent else p.ink2, fontSize = 13.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (active) p.accentSoft else androidx.compose.ui.graphics.Color.Transparent)
            .border(1.dp, if (active) p.accent else p.line, RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp))
}
