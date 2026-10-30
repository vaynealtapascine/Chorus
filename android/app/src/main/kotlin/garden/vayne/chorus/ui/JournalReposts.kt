package garden.vayne.chorus.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import garden.vayne.chorus.designsystem.LocalChorusPalette
import garden.vayne.chorus.designsystem.R

/** The muted "Nairon reposted" line that sits above a reposted card. */
@Composable
internal fun RepostHeader(by: String) {
    val p = LocalChorusPalette.current
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.width(48.dp), contentAlignment = Alignment.CenterEnd) {
            Icon(painterResource(R.drawable.ic_repost), null, Modifier.size(14.dp), tint = p.ink3)
        }
        Text("$by reposted", color = p.ink3, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A repost whose original this device can't read (deleted, or never shared with this account). */
@Composable
internal fun RepostUnavailable(by: String) {
    val p = LocalChorusPalette.current
    RepostHeader(by)
    Text("Original post unavailable", color = p.ink3, style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(start = 72.dp, end = 16.dp, top = 4.dp, bottom = 12.dp))
    HorizontalDivider(color = p.line, thickness = .5.dp)
}
