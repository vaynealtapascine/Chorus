package garden.vayne.chorus.ui

import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import garden.vayne.chorus.data.Blobs
import garden.vayne.chorus.data.ChatAttachment
import garden.vayne.chorus.data.Chorus
import garden.vayne.chorus.designsystem.LocalChorusPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A post's attachments: images share one rounded 16:9 grid (1 full width, 2 side by side, 3 a
 * large left tile with two stacked, 4 a 2×2, more show "+N" on the last), 2 dp apart; any other
 * file keeps its file row below. [who]/[at] caption the full-screen viewer. */
@Composable
internal fun JournalMedia(attachments: List<ChatAttachment>, chorus: Chorus, who: String = "", at: Long = 0L) {
    val images = attachments.filter { it.mime.startsWith("image/") }
    if (images.isNotEmpty()) MediaGrid(images, chorus, who, at)
    for (file in attachments) if (!file.mime.startsWith("image/")) ChatAttachmentView(file, chorus, who, at)
}

@Composable
private fun MediaGrid(images: List<ChatAttachment>, chorus: Chorus, who: String, at: Long) {
    val shown = images.take(4)
    val more = images.size - shown.size
    var viewing by remember { mutableStateOf<ChatAttachment?>(null) }
    val gap = 2.dp
    @Composable fun Tile(index: Int, modifier: Modifier) =
        MediaTile(shown[index], chorus, modifier, if (index == 3) more else 0) { viewing = shown[index] }
    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp))) {
        when (shown.size) {
            1 -> Tile(0, Modifier.fillMaxSize())
            2 -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                Tile(0, Modifier.weight(1f).fillMaxHeight()); Tile(1, Modifier.weight(1f).fillMaxHeight())
            }
            3 -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                Tile(0, Modifier.weight(1f).fillMaxHeight())
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(gap)) {
                    Tile(1, Modifier.weight(1f).fillMaxWidth()); Tile(2, Modifier.weight(1f).fillMaxWidth())
                }
            }
            else -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(gap)) {
                for (row in 0..1) Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    Tile(row * 2, Modifier.weight(1f).fillMaxHeight()); Tile(row * 2 + 1, Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
    }
    val single = shown.singleOrNull()
    if (single != null && single.altText.isNotBlank()) Text(single.altText, color = LocalChorusPalette.current.ink3,
        style = MaterialTheme.typography.bodySmall)
    viewing?.let { MediaViewer(it, chorus, who, at, null, onClose = { viewing = null }) }
}

/** One photo cell outside a post's own grid (the profile's Media tab), opening the full-screen viewer. */
@Composable
internal fun MediaThumb(a: ChatAttachment, chorus: Chorus, modifier: Modifier, who: String, at: Long) {
    var viewing by remember { mutableStateOf(false) }
    MediaTile(a, chorus, modifier, 0) { viewing = true }
    if (viewing) MediaViewer(a, chorus, who, at, null, onClose = { viewing = false })
}

/** One image cell. A spoiler stays covered (and undecoded) until tapped; long-press covers it again. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaTile(a: ChatAttachment, chorus: Chorus, modifier: Modifier, more: Int, onOpen: () -> Unit) {
    val p = LocalChorusPalette.current
    val ctx = LocalContext.current
    var covered by rememberSaveable(a.id) { mutableStateOf(a.spoiler) }
    val hash = a.thumbHash ?: a.blobHash
    val bitmap by produceState<Bitmap?>(null, hash, chorus.device?.session, covered) {
        value = if (covered) null else withContext(Dispatchers.IO) { Blobs.image(ctx.applicationContext, hash, chorus.device) }
    }
    val description = a.altText.ifBlank { a.filename }
    Box(modifier.background(p.surface3).combinedClickable(
        onClickLabel = if (covered) "Reveal spoiler image" else "Open photo",
        onClick = { if (covered) covered = false else onOpen() },
        onLongClick = if (a.spoiler && !covered) ({ covered = true }) else null,
        onLongClickLabel = "Hide image").semantics { contentDescription = if (covered) "Spoiler image" else description },
        contentAlignment = Alignment.Center) {
        val image = bitmap
        when {
            covered -> Text("Spoiler · tap to reveal", color = p.ink2, style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(8.dp))
            image == null -> Text("Loading, or unavailable offline.", color = p.ink3, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(8.dp))
            else -> Image(image.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        if (more > 0 && !covered) Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = .5f)),
            contentAlignment = Alignment.Center) {
            Text("+$more", color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.titleMedium)
        }
    }
}
