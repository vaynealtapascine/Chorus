package garden.vayne.chorus.ui

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import garden.vayne.chorus.data.AttachPicks
import garden.vayne.chorus.designsystem.LocalChorusPalette
import garden.vayne.chorus.designsystem.R
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A photo or video on this device, for the attach sheet's grid (F5.8). */
internal data class DeviceMedia(val uri: Uri, val mime: String, val durationMs: Long)

/** Recent photos straight from MediaStore (only with the person's permission), thumbnails, and camera shots. */
internal object DeviceGallery {
    fun permissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    /** "full", "partial" (Android 14+: only the photos the person chose to share), or "none". */
    fun access(ctx: Context): String {
        fun has(permission: String) = ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED
        return when {
            Build.VERSION.SDK_INT >= 33 && has(Manifest.permission.READ_MEDIA_IMAGES) -> "full"
            Build.VERSION.SDK_INT >= 34 && has(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> "partial"
            Build.VERSION.SDK_INT < 33 && has(Manifest.permission.READ_EXTERNAL_STORAGE) -> "full"
            else -> "none"
        }
    }

    /** The newest photos and videos this app may read, newest first. */
    fun recent(ctx: Context, limit: Int = 150): List<DeviceMedia> {
        val type = MediaStore.Files.FileColumns.MEDIA_TYPE
        val image = MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE
        val video = MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
        val out = ArrayList<DeviceMedia>()
        runCatching {
            ctx.contentResolver.query(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
                arrayOf(MediaStore.Files.FileColumns._ID, type, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.DURATION),
                "$type IN (?, ?)", arrayOf("$image", "$video"), "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c ->
                while (out.size < limit && c.moveToNext()) {
                    val isVideo = c.getInt(1) == video
                    val base = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    out += DeviceMedia(ContentUris.withAppendedId(base, c.getLong(0)),
                        c.getString(2) ?: if (isVideo) "video/mp4" else "image/jpeg", if (isVideo) c.getLong(3) else 0L)
                }
            }
        }
        return out
    }

    private val thumbs = object : LruCache<Uri, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: Uri, value: Bitmap) = value.byteCount
    }

    fun cached(uri: Uri): Bitmap? = thumbs.get(uri)

    /** A small preview of a photo, video or (where its app offers one) document; null otherwise. */
    fun thumbnail(ctx: Context, uri: Uri, px: Int = 256): Bitmap? = thumbs.get(uri)
        ?: runCatching { ctx.contentResolver.loadThumbnail(uri, Size(px, px), null) }.getOrNull()?.also { thumbs.put(uri, it) }

    /** Where the camera writes the next photo: our own files, shared with the camera app for this one shot. */
    fun cameraTarget(ctx: Context): Uri {
        val dir = File(ctx.filesDir, "camera-shots").apply { mkdirs() }
        return FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", File(dir, "photo-${System.currentTimeMillis()}.jpg"))
    }

    /** Delete our own camera shot once it is sent, removed or cancelled; picked files belong to their app. */
    fun discard(ctx: Context, uri: Uri) {
        if (uri.authority != "${ctx.packageName}.files" || uri.pathSegments.firstOrNull() != "camera") return
        val name = uri.lastPathSegment ?: return
        runCatching { File(File(ctx.filesDir, "camera-shots"), name).delete() }
    }
}

/** A page of the attach sheet besides the gallery: poll, content warning, audience, speaker. */
internal class AttachOption(val key: String, val label: String, val icon: Int, val active: Boolean,
    val content: @Composable ColumnScope.() -> Unit)

/**
 * Telegram-style attach sheet (F5.8): the camera and recent photos in a grid, picked in order
 * and added together; Gallery (the system photo picker, no permission needed), File, and the
 * message's other options as round tiles, each opening its own page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AttachSheet(page: String, onPage: (String) -> Unit, already: Int, options: List<AttachOption>,
    onAdd: (List<Uri>) -> Unit, onDismiss: () -> Unit) {
    val p = LocalChorusPalette.current
    val ctx = LocalContext.current
    val motion = rememberChorusMotion()
    var picked by remember { mutableStateOf(emptyList<Uri>()) }
    var reload by remember { mutableIntStateOf(0) }
    val access = remember(reload) { DeviceGallery.access(ctx) }
    val media by produceState(emptyList<DeviceMedia>(), access, reload) {
        value = if (access == "none") emptyList() else withContext(Dispatchers.IO) { DeviceGallery.recent(ctx) }
    }
    val room = (AttachPicks.MAX - already).coerceAtLeast(0)
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { reload++ }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(AttachPicks.MAX)) { uris ->
        if (uris.isNotEmpty()) onAdd(uris.take(room))
    }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        // keep reading them after a restart, so a saved draft's files still send
        for (uri in uris) runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        if (uris.isNotEmpty()) onAdd(uris.take(room))
    }
    var shot by rememberSaveable { mutableStateOf<String?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = shot?.let(Uri::parse)
        shot = null
        if (uri != null) { if (ok) onAdd(listOf(uri)) else DeviceGallery.discard(ctx, uri) }
    }
    // fully open: the tiles under the grid must show without a drag
    ChorusSheet(onDismissRequest = onDismiss, containerColor = p.surface,
        skipPartiallyExpanded = true) {
        val option = options.find { it.key == page }
        if (option == null) Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Add to message", color = p.ink, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp,
                    modifier = Modifier.weight(1f))
                if (room < AttachPicks.MAX) Text("${AttachPicks.MAX - room + picked.size} of ${AttachPicks.MAX}", color = p.ink3,
                    fontSize = 13.sp, lineHeight = 18.sp)
            }
            LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxWidth().heightIn(max = 330.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                item(key = "camera") {
                    GridAction(R.drawable.ic_camera, "Camera", room > 0) {
                        val target = DeviceGallery.cameraTarget(ctx)
                        shot = target.toString()
                        runCatching { camera.launch(target) }.onFailure { shot = null; DeviceGallery.discard(ctx, target) }
                    }
                }
                if (access != "full") item(key = "access") {
                    GridAction(R.drawable.ic_image, if (access == "partial") "Choose more photos" else "Show recent photos", true) {
                        permission.launch(DeviceGallery.permissions())
                    }
                }
                items(media, key = { it.uri.toString() }) { m ->
                    MediaPick(m, AttachPicks.number(picked, m.uri), already + picked.size < AttachPicks.MAX, motion) {
                        picked = AttachPicks.toggle(picked, m.uri, already)
                    }
                }
            }
            if (picked.isNotEmpty()) Button(onClick = { onAdd(picked) }, colors = ButtonDefaults.buttonColors(containerColor = p.accent, contentColor = p.onAccent),
                modifier = Modifier.fillMaxWidth().padding(16.dp).heightIn(min = 48.dp)) { Text(AttachPicks.addLabel(picked.size)) }
            else Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ActionTile(R.drawable.ic_image, "Gallery", enabled = room > 0) {
                    gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                }
                ActionTile(R.drawable.ic_file, "File", enabled = room > 0) { files.launch(arrayOf("*/*")) }
                for (o in options) ActionTile(o.icon, o.label, active = o.active) { onPage(o.key) }
            }
        } else Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChatIconButton(R.drawable.ic_back, "Back to attachments") { onPage("") }
                Text(option.label, color = p.ink, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp)
            }
            option.content(this)
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Done") }
        }
    }
}

@Composable
private fun GridAction(icon: Int, label: String, enabled: Boolean, onClick: () -> Unit) {
    val p = LocalChorusPalette.current
    Column(Modifier.aspectRatio(1f).background(p.surface2).clickable(enabled = enabled, onClick = onClick)
        .alpha(if (enabled) 1f else .4f).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)) {
        ChatIcon(icon, tint = p.accent)
        Text(label, color = p.ink2, fontSize = 12.sp, lineHeight = 16.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun MediaPick(item: DeviceMedia, number: String?, room: Boolean, motion: Boolean, onTap: () -> Unit) {
    val p = LocalChorusPalette.current
    val ctx = LocalContext.current
    val video = item.mime.startsWith("video/")
    val bitmap by produceState(DeviceGallery.cached(item.uri), item.uri) {
        if (value == null) value = withContext(Dispatchers.IO) { DeviceGallery.thumbnail(ctx, item.uri) }
    }
    val scale by animateFloatAsState(if (number != null) .86f else 1f, ChorusMotion.tween(motion, ChorusMotion.Quick), label = "pick")
    val enabled = number != null || room
    Box(Modifier.aspectRatio(1f).background(p.surface3).clickable(enabled = enabled, onClick = onTap)
        .semantics {
            selected = number != null
            contentDescription = (if (video) "Video" else "Photo") + (number?.let { ", picked $it" } ?: "")
        }) {
        val shown = bitmap
        if (shown != null) Image(shown.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().scale(scale).clip(RoundedCornerShape(if (number != null) 10.dp else 0.dp))
                .alpha(if (enabled) 1f else .5f))
        if (video) Text(AttachPicks.duration(item.durationMs), color = Color.White, fontSize = 11.sp, lineHeight = 14.sp,
            modifier = Modifier.align(Alignment.BottomStart).padding(6.dp).background(Color(0x99000000), RoundedCornerShape(6.dp))
                .padding(horizontal = 5.dp, vertical = 1.dp))
        // the pick circle: an empty ring, or the album number
        Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp)
            .then(if (number != null) Modifier.background(p.accent, CircleShape)
                else Modifier.background(Color(0x40000000), CircleShape).border(2.dp, Color.White, CircleShape)),
            contentAlignment = Alignment.Center) {
            if (number != null) Text(number, color = p.onAccent, fontSize = 12.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ActionTile(icon: Int, label: String, active: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val p = LocalChorusPalette.current
    Column(Modifier.width(72.dp).clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .alpha(if (enabled) 1f else .4f).semantics { if (active) stateDescription = "On" }.padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(48.dp).background(if (active) p.accent else p.accentSoft, CircleShape), contentAlignment = Alignment.Center) {
            ChatIcon(icon, tint = if (active) p.onAccent else p.accent)
        }
        Text(label, color = if (active) p.accent else p.ink2, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
    }
}

/** What will go with the message: thumbnails (or a file's type) with remove; tap one for alt text and spoiler. */
@Composable
internal fun AttachmentTray(files: List<PendingAttachment>, onEdit: (Int) -> Unit, onRemove: (Int) -> Unit) {
    LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(top = 8.dp, end = 8.dp, bottom = 2.dp)) {
        itemsIndexed(files, key = { i, a -> "$i:${a.uri}" }) { i, a -> TrayTile(a, { onEdit(i) }, { onRemove(i) }) }
    }
}

@Composable
private fun TrayTile(a: PendingAttachment, onEdit: () -> Unit, onRemove: () -> Unit) {
    val p = LocalChorusPalette.current
    val ctx = LocalContext.current
    val visual = a.mime.startsWith("image/") || a.mime.startsWith("video/")
    val bitmap by produceState(DeviceGallery.cached(a.uri), a.uri) {
        if (value == null && visual) value = withContext(Dispatchers.IO) { DeviceGallery.thumbnail(ctx, a.uri) }
    }
    Box(Modifier.size(76.dp)) {
        Box(Modifier.size(72.dp).align(Alignment.BottomStart).clip(RoundedCornerShape(12.dp)).background(p.surface2)
            .clickable(onClickLabel = "Edit alt text and spoiler", onClick = onEdit)
            .semantics { contentDescription = listOfNotNull(a.name, "spoiler".takeIf { a.spoiler },
                a.alt.takeIf { it.isNotBlank() }?.let { "alt text: $it" }).joinToString(", ") },
            contentAlignment = Alignment.Center) {
            val shown = bitmap
            if (shown != null) Image(shown.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().then(if (a.spoiler) Modifier.blur(10.dp) else Modifier))
            else Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ChatIcon(if (visual) R.drawable.ic_image else R.drawable.ic_file)
                Text(AttachPicks.extension(a.name), color = p.ink2, fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            Row(Modifier.align(Alignment.BottomStart).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                if (a.alt.isNotBlank()) TrayBadge("ALT")
                if (a.spoiler) TrayBadge("Spoiler")
            }
        }
        Box(Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-6).dp).size(30.dp).clip(CircleShape)
            .clickable(onClickLabel = "Remove ${a.name}", role = Role.Button, onClick = onRemove), contentAlignment = Alignment.Center) {
            Box(Modifier.size(22.dp).background(p.ink, CircleShape), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_close), "Remove ${a.name}", Modifier.size(14.dp), tint = p.bg)
            }
        }
    }
}

@Composable
private fun TrayBadge(text: String) {
    Text(text, color = Color.White, fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.background(Color(0x99000000), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp))
}

/** One attachment's alt text and spoiler, with a larger preview; Remove is here too. */
@Composable
internal fun AttachmentEditor(a: PendingAttachment, onSave: (PendingAttachment) -> Unit, onRemove: () -> Unit, onDismiss: () -> Unit) {
    val p = LocalChorusPalette.current
    val ctx = LocalContext.current
    var alt by remember(a.uri) { mutableStateOf(a.alt) }
    var spoiler by remember(a.uri) { mutableStateOf(a.spoiler) }
    val preview by produceState<Bitmap?>(null, a.uri) {
        if (a.mime.startsWith("image/") || a.mime.startsWith("video/"))
            value = withContext(Dispatchers.IO) { runCatching { ctx.contentResolver.loadThumbnail(a.uri, Size(720, 720), null) }.getOrNull() }
    }
    Drawer(onDismiss, a.name, a.mime.substringBefore('/').replaceFirstChar { it.uppercase() }, footer = {
        DrawerButton("Done", { onSave(a.copy(alt = alt, spoiler = spoiler)) })
        DrawerButton("Remove attachment", onRemove, ButtonKind.Quiet)
    }) {
        preview?.let { Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(16.dp)).background(p.surface2)) }
        if (a.mime.startsWith("image/")) ChorusField(alt, { alt = it }, label = { Text("Alt text") },
            placeholder = { Text("Describe the image for people who can't see it") }, minLines = 2,
            modifier = Modifier.fillMaxWidth())
        FormCard { ToggleRow("Spoiler", spoiler, "Hidden until someone taps it") { spoiler = it } }
    }
}

/** A chip under the composer for something the message carries (audience, warning, poll); tap to change it. */
@Composable
internal fun ComposerOptionChip(label: String, icon: Int, onClick: () -> Unit) {
    val p = LocalChorusPalette.current
    Row(Modifier.clip(RoundedCornerShape(16.dp)).background(p.accentSoft).clickable(onClickLabel = "Change", onClick = onClick)
        .heightIn(min = 32.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(painterResource(icon), null, Modifier.size(14.dp), tint = p.accent)
        Text(label, color = p.accent, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 200.dp))
    }
}
