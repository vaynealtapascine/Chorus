package garden.vayne.chorus.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import garden.vayne.chorus.data.Chorus
import garden.vayne.chorus.data.Notices
import garden.vayne.chorus.designsystem.LocalChorusPalette
import garden.vayne.chorus.designsystem.R
import kotlinx.coroutines.delay

/**
 * The strip under the title on every screen while changes can't reach the server (F2.5 away,
 * F10.3 asleep with Retry), or since this phone was signed out from another device, with a way to
 * link it again (D-148). It waits a moment before showing, so a quick reconnect doesn't flash it.
 */
@Composable
internal fun ConnectionBanner(chorus: Chorus, onRelink: () -> Unit) {
    val p = LocalChorusPalette.current
    val status by chorus.status.collectAsState()
    val failures by chorus.connectFailures.collectAsState()
    val network by chorus.network.collectAsState()
    val kind = Notices.connection(status, failures, network)
    var shown by remember { mutableStateOf(Notices.Connection.None) }
    LaunchedEffect(kind) {
        if (kind != Notices.Connection.None && shown == Notices.Connection.None) delay(1500)
        shown = kind
    }
    val motion = rememberChorusMotion()
    val text = Notices.connectionText(shown, chorus.device?.pin != null)
    AnimatedVisibility(text != null,
        enter = if (motion) expandVertically(ChorusMotion.tween(true)) + fadeIn(ChorusMotion.tween(true)) else androidx.compose.animation.EnterTransition.None,
        exit = if (motion) shrinkVertically(ChorusMotion.tween(true)) + fadeOut(ChorusMotion.tween(true)) else androidx.compose.animation.ExitTransition.None) {
        val asleep = shown == Notices.Connection.Asleep
        val signedOut = shown == Notices.Connection.SignedOut
        val tone = if (asleep) p.danger else p.warn
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).background(tone.copy(alpha = .15f))
            .semantics { liveRegion = LiveRegionMode.Polite }.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Glyph(if (asleep) R.drawable.ic_moon else if (signedOut) R.drawable.ic_link else R.drawable.ic_wifi_off, tone, 18.dp)
            Text(text.orEmpty(), color = p.ink, fontSize = 13.sp, lineHeight = 18.sp, maxLines = if (signedOut) 3 else 2, modifier = Modifier.weight(1f))
            if (asleep) TextButton(onClick = { chorus.reconnectNow() }) { Text("Retry", color = tone, fontWeight = FontWeight.SemiBold) }
            if (signedOut) TextButton(onClick = onRelink) { Text("Link again", color = tone, fontWeight = FontWeight.SemiBold) }
        }
    }
}

/** "Catching its breath. Try again in 12 seconds.": a pill above the bottom bar, counting down (F10.7). */
@Composable
internal fun NoticeToast(modifier: Modifier = Modifier) {
    val p = LocalChorusPalette.current
    var until by remember { mutableStateOf<Long?>(null) }
    var unknownWait by remember { mutableStateOf(false) }
    var left by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) {
        Notices.flow.collect { n ->
            if (n is Notices.Notice.SlowDown) {
                unknownWait = n.seconds == null
                until = System.currentTimeMillis() + (n.seconds ?: 4) * 1000L
            }
        }
    }
    LaunchedEffect(until) {
        val end = until ?: return@LaunchedEffect
        while (true) {
            val remaining = ((end - System.currentTimeMillis() + 999) / 1000).toInt()
            if (remaining <= 0) break
            left = remaining
            delay(250)
        }
        until = null
    }
    val motion = rememberChorusMotion()
    Box(modifier) {
        AnimatedVisibility(until != null, enter = if (motion) fadeIn(ChorusMotion.tween(true)) else androidx.compose.animation.EnterTransition.None,
            exit = if (motion) fadeOut(ChorusMotion.tween(true)) else androidx.compose.animation.ExitTransition.None) {
            Row(Modifier.widthIn(max = 340.dp).clip(RoundedCornerShape(19.dp)).background(p.surface2)
                .semantics { liveRegion = LiveRegionMode.Polite }.heightIn(min = 38.dp).padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Glyph(R.drawable.ic_clock, p.ink2, 16.dp)
                Text(Notices.slowDownText(if (unknownWait) null else left), color = p.ink, fontSize = 14.sp, lineHeight = 18.sp)
            }
        }
    }
}
