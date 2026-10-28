package garden.vayne.chorus.ui

import android.graphics.ImageDecoder
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import garden.vayne.chorus.designsystem.LocalChorusPalette
import garden.vayne.chorus.designsystem.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Compact picker previews; only the existing Send path stages/uploads the selected files. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun JournalAttachments(files: List<PendingAttachment>, enabled: Boolean,
    onChange: (Int, PendingAttachment) -> Unit, onRemove: (Int) -> Unit) {
    val p = LocalChorusPalette.current
    var editing by remember { mutableStateOf<PendingAttachment?>(null) }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        itemsIndexed(files, key = { i, file -> "$i:${file.uri}" }) { index, file ->
            val ctx = LocalContext.current
            val preview by produceState<ImageBitmap?>(null, file.uri) {
                value = if (!file.mime.startsWith("image/")) null else withContext(Dispatchers.IO) {
                    runCatching { ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, file.uri)) { decoder, info, _ ->
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        val scale = minOf(1f, 256f / maxOf(info.size.width, info.size.height))
                        decoder.setTargetSize(maxOf(1, (info.size.width * scale).toInt()), maxOf(1, (info.size.height * scale).toInt()))
                    }.asImageBitmap() }.getOrNull()
                }
            }
            Column(Modifier.width(92.dp)) {
                Box(Modifier.size(84.dp).clip(RoundedCornerShape(12.dp)).background(p.surface2)
                    .clickable(enabled = enabled, onClickLabel = "Attachment details for ${file.name}") { editing = file }) {
                    if (preview != null) Image(preview!!, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ChatIcon(R.drawable.ic_file) }
                    IconButton(enabled = enabled, onClick = { onRemove(index) }, modifier = Modifier.align(Alignment.TopEnd).size(48.dp)
                        .background(p.surface.copy(alpha = .85f), RoundedCornerShape(bottomStart = 12.dp))) {
                        ChatIcon(R.drawable.ic_delete, "Remove ${file.name}")
                    }
                }
                Text(file.name, style = MaterialTheme.typography.bodySmall, color = p.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    val chosen = editing
    val index = files.indexOfFirst { it.uri == chosen?.uri }
    if (chosen != null && index >= 0) ModalBottomSheet(onDismissRequest = { editing = null }, containerColor = p.surface) {
        val file = files[index]
        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(file.name, style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(file.spoiler, onCheckedChange = { onChange(index, file.copy(spoiler = it)) })
                Text("Hide as spoiler", style = MaterialTheme.typography.bodyMedium)
            }
            if (file.mime.startsWith("image/")) OutlinedTextField(file.alt, { onChange(index, file.copy(alt = it)) },
                label = { Text("Image description (alt text)") }, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = { editing = null }) { Text("Done") }
        }
    }
}
