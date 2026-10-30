package garden.vayne.chorus.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import garden.vayne.chorus.data.Chorus
import garden.vayne.chorus.data.JournalPost
import garden.vayne.chorus.data.JournalPresentation
import garden.vayne.chorus.data.Model
import garden.vayne.chorus.data.PostCompose
import garden.vayne.chorus.designsystem.LocalChorusPalette
import kotlinx.coroutines.launch

/** Change who can read an existing post (`post.set_visibility`, queued offline like any op). Making
 * a post readable by more people says so before the change is written; narrowing needs no warning. */
@Composable
internal fun JournalAudienceDialog(post: JournalPost, model: Model, chorus: Chorus, onDismiss: () -> Unit) {
    val p = LocalChorusPalette.current
    val scope = rememberCoroutineScope()
    var choice by remember(post.id) { mutableStateOf(post.visibility) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val widening = JournalPresentation.widens(post.visibility, choice)
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Who can see this post?") },
        text = { Column {
            for (mode in JournalPresentation.AUDIENCES) Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .selectable(selected = choice == mode, enabled = !busy, role = Role.RadioButton) { choice = mode },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RadioButton(selected = choice == mode, onClick = null, enabled = !busy)
                Text(JournalPresentation.audience(mode, model.isPerson), color = p.ink)
            }
            if (widening) Text("More people will be able to read this post.", color = p.ink2,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            if (error != null) Text(error.orEmpty(), color = p.danger, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp))
        } },
        confirmButton = { TextButton(enabled = !busy && choice != post.visibility && choice in JournalPresentation.AUDIENCES, onClick = {
            busy = true; error = null
            scope.launch {
                try {
                    chorus.create("post.set_visibility", post.id, PostCompose.visibilityPayload(choice))
                    onDismiss()
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { error = "Could not change the audience. Try again." }
                finally { busy = false }
            }
        }) { Text(if (widening) "Make visible" else "Change audience") } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } })
}
