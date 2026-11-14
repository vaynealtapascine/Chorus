package garden.vayne.chorus.ui

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import garden.vayne.chorus.data.Blobs
import garden.vayne.chorus.data.Chorus
import garden.vayne.chorus.data.CustomEmoji
import garden.vayne.chorus.data.CustomEmojis
import garden.vayne.chorus.data.DeviceRecord
import garden.vayne.chorus.designsystem.LocalChorusPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The server's custom emoji by id (D-054), for every rich text that draws entities; retired ones included. */
internal val LocalCustomEmoji = compositionLocalOf<Map<String, CustomEmoji>> { emptyMap() }

/** Provide [LocalCustomEmoji] from the projection; only readers recompose when the set changes. */
@Composable
internal fun ProvideCustomEmoji(chorus: Chorus, content: @Composable () -> Unit) {
    val model by chorus.model.collectAsState()
    val rows by remember { derivedStateOf { model.customEmoji } }
    val byId = remember(rows) { rows.associateBy { it.id } }
    CompositionLocalProvider(LocalCustomEmoji provides byId, content = content)
}

/** Emoji are small images in their own cache kind (data/Blobs.kt, like avatars): at most 512 KB and 128 px. */
internal object EmojiBlobs {
    private val shown = LruCache<String, Bitmap>(96)
    fun cached(hash: String): Bitmap? = shown.get(hash)
    fun load(ctx: Context, hash: String, device: DeviceRecord?): Bitmap? =
        Blobs.image(ctx, hash, device, kind = "emoji", maxPx = CustomEmojis.SIZE, maxBytes = 512L * 1024)?.also { shown.put(hash, it) }
}

/** One custom emoji as a picture; a soft tile while it loads or when it is not on this device. */
@Composable
internal fun EmojiImage(emoji: CustomEmoji, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val device = Chorus.get(ctx).device
    val bitmap by produceState(EmojiBlobs.cached(emoji.blobHash), emoji.blobHash, device?.session) {
        value = EmojiBlobs.cached(emoji.blobHash)
        if (value == null) value = withContext(Dispatchers.IO) { EmojiBlobs.load(ctx.applicationContext, emoji.blobHash, device) }
    }
    val image = bitmap
    if (image != null) Image(image.asImageBitmap(), contentDescription = emoji.token, modifier = modifier, contentScale = ContentScale.Fit)
    else Box(modifier.background(LocalChorusPalette.current.surface3, RoundedCornerShape(6.dp)).semantics { contentDescription = emoji.token })
}

/** The composer's emoji drawer: the reaction set, and a Custom tab when the server has any. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EmojiTray(custom: List<CustomEmoji>, unicode: List<String>, onUnicode: (String) -> Unit,
    onCustom: (CustomEmoji) -> Unit, onDismiss: () -> Unit) {
    val p = LocalChorusPalette.current
    var tab by rememberSaveable { mutableStateOf(0) }
    val groups = remember(custom) { CustomEmojis.grouped(custom) }
    Drawer(onDismiss, "Emoji") {
        if (groups.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoiceChip("Emoji", { tab = 0 }, active = tab == 0)
            ChoiceChip("Custom", { tab = 1 }, active = tab == 1)
        }
        if (tab == 0 || groups.isEmpty()) FlowRow(Modifier.fillMaxWidth()) {
            for (emoji in unicode) Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClickLabel = "Add $emoji") { onUnicode(emoji) },
                contentAlignment = Alignment.Center) { Text(emoji, fontSize = 26.sp, lineHeight = 30.sp) }
        } else for ((category, items) in groups) {
            Text(category, color = p.ink3, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 4.dp))
            FlowRow(Modifier.fillMaxWidth()) {
                for (e in items) key(e.id) {
                    Column(Modifier.width(72.dp).clip(RoundedCornerShape(12.dp)).clickable(onClickLabel = "Add ${e.token}") { onCustom(e) }
                        .padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        EmojiImage(e, Modifier.size(36.dp))
                        Text(e.token, color = p.ink3, fontSize = 11.sp, lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 4.dp))
                    }
                }
            }
        }
    }
}

private fun Modifier.fillMaxWidth(): Modifier = this.then(Modifier.fillMaxWidth())

/** `:name` typed in the composer: the live emoji that match, one tap completes it. */
@Composable
internal fun EmojiSuggestions(matches: List<CustomEmoji>, onPick: (CustomEmoji) -> Unit) {
    val p = LocalChorusPalette.current
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(matches, key = { it.id }) { e ->
            Row(Modifier.clip(RoundedCornerShape(20.dp)).background(p.surface2).clickable(role = Role.Button, onClickLabel = "Add ${e.token}") { onPick(e) }
                .heightIn(min = 44.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EmojiImage(e, Modifier.size(24.dp))
                Text(e.token, color = p.ink, fontSize = 14.sp, lineHeight = 19.sp, maxLines = 1)
            }
        }
    }
}
