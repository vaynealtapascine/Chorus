package garden.vayne.chorus.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import garden.vayne.chorus.data.Updater
import garden.vayne.chorus.designsystem.LocalChorusPalette
import garden.vayne.chorus.designsystem.R

/** "Chorus 0.1.595 is ready · Update": a newer build found when the app opened (CLIENTS.md §5a).
 * Updating closes the app, so it waits for a tap; leaving the app installs it when Android needs
 * no prompt. ✕ hides it until the next version. */
@Composable
internal fun UpdateBanner() {
    val p = LocalChorusPalette.current
    val ctx = LocalContext.current
    val state by Updater.state.collectAsState()
    var hidden by rememberSaveable { mutableStateOf<String?>(null) }
    val release = when (val s = state) {
        is Updater.State.Available -> s.release
        is Updater.State.Downloading -> s.release
        is Updater.State.Ready -> s.release
        is Updater.State.NeedsPermission -> s.release
        is Updater.State.Installing -> s.release
        is Updater.State.Failed -> s.release
        Updater.State.Idle -> null
    }
    val motion = rememberChorusMotion()
    AnimatedVisibility(release != null && hidden != release.versionName,
        enter = if (motion) expandVertically(ChorusMotion.tween(true)) + fadeIn(ChorusMotion.tween(true)) else androidx.compose.animation.EnterTransition.None,
        exit = if (motion) shrinkVertically(ChorusMotion.tween(true)) + fadeOut(ChorusMotion.tween(true)) else androidx.compose.animation.ExitTransition.None) {
        val name = release?.versionName.orEmpty()
        val (text, action) = when (val s = state) {
            is Updater.State.Available -> "Chorus $name is available (${(s.release.size / 1_048_576.0).let { "%.0f".format(it) }} MB)" to "Download"
            is Updater.State.Downloading -> "Downloading Chorus $name… ${(s.progress * 100).toInt()}%" to null
            is Updater.State.Ready -> "Chorus $name is ready" to "Update"
            is Updater.State.NeedsPermission -> "Allow Chorus to install its own updates" to "Allow"
            is Updater.State.Installing -> "Installing Chorus $name…" to null
            is Updater.State.Failed -> s.message to "Retry"
            Updater.State.Idle -> "" to null
        }
        Column(Modifier.fillMaxWidth().background(p.accentSoft)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(28.dp).clip(CircleShape).background(p.accent), contentAlignment = Alignment.Center) {
                    ChatIcon(R.drawable.ic_down, null, p.onAccent)
                }
                Text(text, color = p.ink, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                if (action != null) TextButton(onClick = { Updater.proceed(ctx) }) {
                    Text(action, color = p.accent, fontWeight = FontWeight.SemiBold)
                }
                if (state !is Updater.State.Installing) ChatIconButton(R.drawable.ic_close, "Hide until the next version") { hidden = name }
            }
            (state as? Updater.State.Downloading)?.let { s ->
                LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = p.accent, trackColor = p.accentSoft)
            }
        }
    }
}
