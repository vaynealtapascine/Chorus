package garden.vayne.chorus.ui

import androidx.compose.ui.unit.sp
import garden.vayne.chorus.designsystem.R
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
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
    var choice by remember(post.id) { mutableStateOf(post.audience) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val widening = choice.possiblyWidens(post.audience)
    Drawer(onDismiss, "Who can see this post?", "Tap the audience to change it.", dismissible = !busy, footer = {
        DrawerButton(if (widening) "Make visible" else "Change audience", {
            busy = true; error = null
            scope.launch {
                try {
                    chorus.create("post.set_visibility", post.id, PostCompose.visibilityPayload(choice, model.sharingBuckets.map { it.first }.toSet()))
                    onDismiss()
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { error = "Could not change the audience. Try again." }
                finally { busy = false }
            }
        }, enabled = !choice.same(post.audience), busy = busy)
    }) {
        FormCard {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Audience", color = p.ink, fontSize = 15.sp, modifier = Modifier.weight(1f))
                AudienceChip("This post", choice, own = true, buckets = model.sharingBuckets,
                    person = model.isPerson, enabled = !busy, onChange = { it?.let { choice = it } }, looks = model.bucketLooks)
            }
        }
        if (widening) FormNote("More people will be able to read this post.", R.drawable.ic_globe, warning = true)
        if (error != null) FormNote(error.orEmpty(), warning = true)
    }
}
