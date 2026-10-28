package garden.vayne.chorus.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import garden.vayne.chorus.data.*
import garden.vayne.chorus.designsystem.LocalChorusPalette
import garden.vayne.chorus.designsystem.R

/** One action row across own/shared posts and the fixed detail footer. The confirmation is a
 * contextual identity disclosure, before a public reaction is written (not repeated feed copy). */
@Composable
internal fun JournalActions(reactions: List<PostReaction>, member: Member?, enabled: Boolean = true,
    onReply: (() -> Unit)? = null, onReact: (() -> Unit)? = null) {
    val p = LocalChorusPalette.current
    var confirm by remember { mutableStateOf(false) }
    val selected = member != null && reactions.any { it.emoji == PostReactions.HEART && it.memberId == member.id }
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        if (onReply != null) TextButton(onClick = onReply, contentPadding = PaddingValues(horizontal = 0.dp)) {
            ChatIcon(R.drawable.ic_reply, tint = p.ink3)
            Spacer(Modifier.width(6.dp)); Text("Reply", color = p.ink3)
        }
        if (member != null && onReact != null) TextButton(enabled = enabled, onClick = { confirm = true },
            modifier = Modifier.semantics { contentDescription = "${if (selected) "Remove heart" else "Heart"} as ${member.shownName}; visible to post readers" }) {
            ChatIcon(R.drawable.ic_heart, tint = if (selected) p.danger else p.ink3)
            Text(" ${reactions.count { it.emoji == PostReactions.HEART }}", color = if (selected) p.danger else p.ink3)
        } else if (reactions.isNotEmpty()) TextButton(onClick = { confirm = true }) {
            Text("${reactions.count { it.emoji == PostReactions.HEART }} hearts", style = MaterialTheme.typography.bodySmall, color = p.ink3)
        }
    }
    if (confirm && member != null && onReact != null) AlertDialog(onDismissRequest = { confirm = false },
        title = { Text(if (selected) "Remove ${member.shownName}’s heart?" else "React as ${member.shownName}?") },
        text = { Column {
            Text("Post readers can see this member’s name with the reaction.")
            if (reactions.isNotEmpty()) Text(reactions.joinToString(" · ") { "${it.emoji} ${it.memberName}" }, style = MaterialTheme.typography.bodySmall)
        } },
        confirmButton = { TextButton(enabled = enabled, onClick = { confirm = false; onReact() }) { Text(if (selected) "Remove heart" else "Heart") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } })
    else if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Reactions") },
        text = { Text(reactions.joinToString(" · ") { "${it.emoji} ${it.memberName}" }) },
        confirmButton = { TextButton(onClick = { confirm = false }) { Text("Done") } })
}
