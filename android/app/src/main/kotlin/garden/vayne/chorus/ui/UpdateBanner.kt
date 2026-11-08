package garden.vayne.chorus.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.sp
import garden.vayne.chorus.data.Chorus
import garden.vayne.chorus.data.Updater
import garden.vayne.chorus.designsystem.LocalChorusPalette
import garden.vayne.chorus.designsystem.R

/**
 * A newer build (CLIENTS.md §5a, F10.5). Once it's fetched it opens as a drawer: the version, where
 * it's from, what's new, Later and Install. Fetching shows as a slim banner; Later folds the drawer
 * into that banner (tap to reopen, ✕ hides it until the next version). Leaving the app installs a
 * fetched build when Android needs no prompt.
 */
@Composable
internal fun UpdateBanner() {
    val p = LocalChorusPalette.current
    val ctx = LocalContext.current
    val state by Updater.state.collectAsState()
    var hidden by rememberSaveable { mutableStateOf<String?>(null) }
    var later by rememberSaveable { mutableStateOf<String?>(null) }
    val release = when (val s = state) {
        is Updater.State.Available -> s.release
        is Updater.State.Downloading -> s.release
        is Updater.State.Ready -> s.release
        is Updater.State.NeedsPermission -> s.release
        is Updater.State.Installing -> s.release
        is Updater.State.Failed -> s.release
        Updater.State.Idle -> null
    }
    val name = release?.versionName.orEmpty()
    val fetched = state is Updater.State.Ready || state is Updater.State.NeedsPermission || state is Updater.State.Installing ||
        state is Updater.State.Failed
    val drawer = release != null && fetched && later != name && hidden != name
    val motion = rememberChorusMotion()
    AnimatedVisibility(release != null && !drawer && hidden != name,
        enter = if (motion) expandVertically(ChorusMotion.tween(true)) + fadeIn(ChorusMotion.tween(true)) else androidx.compose.animation.EnterTransition.None,
        exit = if (motion) shrinkVertically(ChorusMotion.tween(true)) + fadeOut(ChorusMotion.tween(true)) else androidx.compose.animation.ExitTransition.None) {
        val (text, action) = when (val s = state) {
            is Updater.State.Available -> "Chorus $name · ${megabytes(s.release.size)}" to "Download"
            is Updater.State.Downloading -> "Downloading Chorus $name… ${(s.progress * 100).toInt()}%" to null
            is Updater.State.Installing -> "Installing Chorus $name…" to null
            else -> "Chorus $name is ready" to "Update"
        }
        Column(Modifier.fillMaxWidth().background(p.accentSoft).then(if (fetched) Modifier.clickable { later = null } else Modifier)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(28.dp).clip(CircleShape).background(p.accent), contentAlignment = Alignment.Center) {
                    ChatIcon(R.drawable.ic_down, null, p.onAccent)
                }
                Text(text, color = p.ink, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                if (action != null) TextButton(onClick = { if (fetched) later = null else Updater.proceed(ctx) }) {
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
    if (drawer && release != null) UpdateDrawer(state, release, onLater = { later = name }) { Updater.proceed(ctx) }
}

private fun megabytes(bytes: Long) = "%.0f MB".format(bytes / 1_048_576.0)

/** F10.5: "Chorus 0.1.N is ready / From <server> · 9 MB", What's new, Later + Install. */
@Composable
private fun UpdateDrawer(state: Updater.State, release: Updater.Release, onLater: () -> Unit, onInstall: () -> Unit) {
    val p = LocalChorusPalette.current
    val ctx = LocalContext.current
    val from = Chorus.get(ctx).device?.let(::serverName)
    val installing = state is Updater.State.Installing
    Drawer(onLater, "Chorus ${release.versionName} is ready", listOfNotNull(from?.let { "From $it" }, megabytes(release.size)).joinToString(" · "),
        leading = {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(p.accent), contentAlignment = Alignment.Center) {
                Text("C", color = p.onAccent, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
        }, dismissible = !installing, footer = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DrawerButton("Later", onLater, ButtonKind.Neutral, enabled = !installing, modifier = Modifier.width(96.dp))
                DrawerButton(when (state) {
                    is Updater.State.NeedsPermission -> "Allow installing"
                    is Updater.State.Failed -> "Try again"
                    else -> "Install"
                }, onInstall, busy = installing, modifier = Modifier.weight(1f))
            }
        }) {
        if (release.changelog.isNotEmpty()) FormCard {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("What's new", color = p.ink3, fontSize = 13.sp)
                for (line in release.changelog) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Glyph(R.drawable.ic_check, p.accent, 18.dp)
                    Text(line, color = p.ink, fontSize = 15.sp, lineHeight = 20.sp)
                }
            }
        }
        (state as? Updater.State.Failed)?.let { Text(it.message, color = p.danger, fontSize = 14.sp) }
        Text("Installing restarts the app. Drafts and unsent messages are kept.", color = p.ink3, fontSize = 13.sp, lineHeight = 18.sp)
    }
}
