package garden.vayne.chorus.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import garden.vayne.chorus.data.Blobs
import garden.vayne.chorus.data.Chorus
import garden.vayne.chorus.data.CustomEmoji
import garden.vayne.chorus.data.CustomEmojis
import garden.vayne.chorus.data.Model
import garden.vayne.chorus.data.ProfileCrop
import garden.vayne.chorus.data.UploadWork
import garden.vayne.chorus.designsystem.LocalChorusPalette
import garden.vayne.chorus.designsystem.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Custom emoji (F5.12, D-054), for the server's admin (web: Chat details → Manage custom emoji): the
 * set as a grid with an Add tile; a tapped emoji shows its shortcode, aliases and category, and Retire.
 * A retired emoji leaves the picker; messages that used it keep showing it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EmojiManagerDrawer(chorus: Chorus, model: Model, onDismiss: () -> Unit) {
    val p = LocalChorusPalette.current
    val scope = rememberCoroutineScope()
    val live = remember(model.customEmoji) { CustomEmojis.active(model.customEmoji) }
    var selected by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    var retiring by remember { mutableStateOf<CustomEmoji?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val chosen = live.find { it.id == selected }
    Drawer(onDismiss, "Custom emoji") {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (e in live) key(e.id) {
                val on = e.id == selected
                Column(Modifier.width(76.dp).clip(RoundedCornerShape(14.dp)).then(if (on) Modifier.border(1.5.dp, p.accent, RoundedCornerShape(14.dp)) else Modifier)
                    .clickable(onClickLabel = e.token) { selected = if (on) null else e.id }.padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    EmojiImage(e, Modifier.size(44.dp))
                    Text(e.token, color = if (on) p.accent else p.ink3, fontSize = 11.sp, lineHeight = 14.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp))
                }
            }
            Column(Modifier.width(76.dp).clip(RoundedCornerShape(14.dp)).dashedBorder(1.dp, p.line, RoundedCornerShape(14.dp))
                .clickable(onClickLabel = "Add an emoji") { adding = true }.padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { Glyph(R.drawable.ic_plus, p.ink2, 22.dp) }
                Text("Add", color = p.ink3, fontSize = 11.sp, lineHeight = 14.sp)
            }
        }
        if (chosen != null) {
            FormCard {
                FormInfoRow("Shortcode", chosen.token)
                if (chosen.aliases.isNotEmpty()) { FormDivider(); FormInfoRow("Aliases", chosen.aliases.joinToString(", ") { ":$it:" }) }
                FormDivider()
                FormInfoRow("Category", chosen.category)
            }
            DrawerButton("Retire", { retiring = chosen }, ButtonKind.Danger, busy = busy)
        }
        error?.let { Text(it, color = p.danger, fontSize = 14.sp) }
    }
    if (adding) EmojiAddDrawer(chorus, model) { adding = false }
    retiring?.let { e ->
        ConfirmDrawer("Retire ${e.token}?", null, "Retire", {
            retiring = null; busy = true; error = null
            scope.launch {
                try { chorus.create("emoji.delete", e.id, JSONObject(), scope = "server"); selected = null }
                catch (x: CancellationException) { throw x }
                catch (x: Exception) { error = x.message ?: "Could not retire it." }
                finally { busy = false }
            }
        }, { retiring = null }, danger = true)
    }
}

/** What the picked image is: its file, its type, and the first frame to show. */
private class PickedEmoji(val file: File, val mime: String, val bitmap: Bitmap, val width: Int, val height: Int)

/** Add an emoji (web's form): an image (PNG, WebP or GIF), cropped square for the still ones, a name, aliases and a category. */
@Composable
private fun EmojiAddDrawer(chorus: Chorus, model: Model, onDismiss: () -> Unit) {
    val p = LocalChorusPalette.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var picked by remember { mutableStateOf<PickedEmoji?>(null) }
    var name by remember { mutableStateOf("") }
    var aliases by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(CustomEmojis.DEFAULT_CATEGORY) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var x by remember { mutableFloatStateOf(.5f) }
    var y by remember { mutableFloatStateOf(.5f) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) { onDispose { picked?.file?.delete() } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            busy = true; error = null
            try {
                val next = withContext(Dispatchers.IO) {
                    val mime = ctx.contentResolver.getType(uri).orEmpty()
                    require(mime in CustomEmojis.IMAGE_TYPES) { "Use PNG, WebP, or GIF." }
                    val file = File.createTempFile("emoji-source-", ".image", ctx.cacheDir)
                    try {
                        ctx.contentResolver.openInputStream(uri)?.use { src -> file.outputStream().use { out ->
                            val buffer = ByteArray(16 * 1024)
                            var total = 0
                            while (true) {
                                val n = src.read(buffer)
                                if (n < 0) break
                                total += n
                                require(total <= 10 * 1024 * 1024) { "Choose an image under 10 MB." }
                                out.write(buffer, 0, n)
                            }
                        } } ?: error("Could not open this image. Choose it again.")
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeFile(file.path, it) }
                        if (mime == "image/gif") CustomEmojis.gifProblem(bounds.outWidth, bounds.outHeight, file.length())?.let { error(it) }
                        PickedEmoji(file, mime, decodeProfileImage(file), bounds.outWidth, bounds.outHeight)
                    } catch (e: Exception) { file.delete(); throw e }
                }
                picked?.file?.delete()
                picked = next; zoom = 1f; x = .5f; y = .5f
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Could not read this image." }
            finally { busy = false }
        }
    }
    fun add() {
        val image = picked
        error = CustomEmojis.problem(model.customEmoji, name, image != null)
        if (image == null || error != null) return
        val account = chorus.device?.accountId ?: run { error = "Not signed in."; return }
        busy = true
        scope.launch {
            try {
                val animated = image.mime == "image/gif"
                val (bytes, mime) = withContext(Dispatchers.IO) {
                    if (animated) image.file.readBytes() to image.mime
                    else {
                        val src = image.bitmap
                        val r = ProfileCrop.rect(src.width, src.height, false, zoom, x, y)
                        val crop = Bitmap.createBitmap(src, r.x, r.y, r.width, r.height)
                        val square = Bitmap.createScaledBitmap(crop, CustomEmojis.SIZE, CustomEmojis.SIZE, true)
                        try {
                            ByteArrayOutputStream().also { square.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray() to "image/png"
                        } finally {
                            if (square !== crop) square.recycle()
                            if (crop !== src) crop.recycle()
                        }
                    }
                }
                check(bytes.size <= CustomEmojis.MAX_BYTES) { "Cropped emoji exceeds 256 KB." }
                val hash = withContext(Dispatchers.IO) {
                    val staged = Blobs.stage(ctx, bytes.inputStream(), mime, account)
                    val device = chorus.device?.takeIf { it.accountId == account } ?: error("The account changed. Reopen this.")
                    // keep it on the device now, so it shows offline whatever the upload does
                    check(Blobs.keep(ctx, staged.hash, device)) { "Could not keep this image on the device. Try again." }
                    staged.hash
                }
                chorus.create("emoji.create", chorus.newId(), CustomEmojis.createPayload(name, aliases, category, hash, animated), scope = "server")
                UploadWork.enqueue(ctx)
                onDismiss()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Could not add the emoji. Try again." }
            finally { busy = false }
        }
    }
    Drawer(onDismiss, "New emoji", dismissible = !busy, footer = {
        error?.let { Text(it, color = p.danger, fontSize = 14.sp) }
        DrawerButton("Add emoji", ::add, busy = busy)
    }) {
        val image = picked
        if (image == null) FormCard {
            NavRow("Choose an image", R.drawable.ic_image, enabled = !busy) { picker.launch("image/*") }
        } else {
            val still = image.mime != "image/gif"
            val rect = remember(image, zoom, x, y) { ProfileCrop.rect(image.bitmap.width, image.bitmap.height, false, zoom, x, y) }
            val pixels = remember(image) { image.bitmap.asImageBitmap() }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(128.dp).clip(RoundedCornerShape(16.dp)).background(p.surface3)
                    .clickable(enabled = !busy, onClickLabel = "Choose another image") { picker.launch("image/*") }
                    .semantics { contentDescription = "Emoji preview" }) {
                    val crop = if (still) rect else ProfileCrop.Rect(0, 0, image.bitmap.width, image.bitmap.height)
                    drawImage(pixels, srcOffset = IntOffset(crop.x, crop.y), srcSize = IntSize(crop.width, crop.height),
                        dstSize = IntSize(size.width.toInt(), size.height.toInt()))
                }
            }
            if (still) {
                Text("Zoom", color = p.ink2, fontSize = 13.sp)
                Slider(zoom, { zoom = it }, valueRange = 1f..3f, enabled = !busy, modifier = Modifier.semantics { contentDescription = "Crop zoom" })
                Text("Left and right", color = p.ink2, fontSize = 13.sp)
                Slider(x, { x = it }, enabled = !busy, modifier = Modifier.semantics { contentDescription = "Crop horizontal position" })
                Text("Up and down", color = p.ink2, fontSize = 13.sp)
                Slider(y, { y = it }, enabled = !busy, modifier = Modifier.semantics { contentDescription = "Crop vertical position" })
            }
        }
        ChorusField(name, { name = it.lowercase().filter { c -> c in 'a'..'z' || c in '0'..'9' || c == '_' }.take(32) }, Modifier.fillMaxWidth(),
            enabled = !busy, singleLine = true, label = { Text("Name") })
        ChorusField(aliases, { aliases = it.take(200) }, Modifier.fillMaxWidth(), enabled = !busy, singleLine = true, label = { Text("Aliases, comma separated") })
        ChorusField(category, { category = it.take(40) }, Modifier.fillMaxWidth(), enabled = !busy, singleLine = true, label = { Text("Category") })
    }
}
