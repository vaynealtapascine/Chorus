package garden.vayne.chorus.ui

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import garden.vayne.chorus.InspectionDialogContent
import garden.vayne.chorus.data.Blobs
import garden.vayne.chorus.data.ProfileCrop
import garden.vayne.chorus.designsystem.LocalChorusPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/** Source decoding is bounded and EXIF-aware on API 29+. Sliders and preview share geometry. */
internal fun decodeProfileImage(file: File): Bitmap {
    require(file.length() <= 10 * 1024 * 1024) { "Choose an image under 10 MB." }
    return ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
        require(info.size.width.toLong() * info.size.height <= 100_000_000) { "Choose an image under 100 megapixels." }
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val scale = minOf(1f, 1600f / maxOf(info.size.width, info.size.height))
        decoder.setTargetSize(maxOf(1, (info.size.width * scale).toInt()), maxOf(1, (info.size.height * scale).toInt()))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProfileImageCrop(path: String, banner: Boolean, account: String, onCancel: () -> Unit, onApply: (String) -> Unit) {
    val p = LocalChorusPalette.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var bitmap by remember(path) { mutableStateOf<Bitmap?>(null) }
    var error by remember(path) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var zoom by rememberSaveable(path) { mutableFloatStateOf(1f) }
    var x by rememberSaveable(path) { mutableFloatStateOf(.5f) }
    var y by rememberSaveable(path) { mutableFloatStateOf(.5f) }
    LaunchedEffect(path) {
        try { bitmap = withContext(Dispatchers.IO) { decodeProfileImage(File(path)) } }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "Could not open that image. Choose another." }
    }
    ModalBottomSheet(onDismissRequest = { if (!busy) onCancel() }, containerColor = p.surface,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        InspectionDialogContent {
            // Reserve the viewport before decoding. Otherwise the loading-sized expanded
            // anchor can leave newly loaded crop controls below the screen (Material sheet).
            Column(Modifier.fillMaxWidth().fillMaxHeight(.9f).verticalScroll(rememberScrollState()).padding(20.dp).navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (banner) "Crop banner" else "Crop avatar", style = MaterialTheme.typography.titleLarge)
                val image = bitmap
                if (image != null) {
                    val rect = ProfileCrop.rect(image.width, image.height, banner, zoom, x, y)
                    val pixels = remember(image) { image.asImageBitmap() }
                    Canvas(Modifier.fillMaxWidth().aspectRatio(if (banner) 3f else 1f).clip(RoundedCornerShape(12.dp))) {
                        drawImage(pixels, srcOffset = IntOffset(rect.x, rect.y), srcSize = IntSize(rect.width, rect.height),
                            dstSize = IntSize(size.width.toInt(), size.height.toInt()))
                    }
                    Text("Zoom", color = p.ink2)
                    Slider(zoom, { zoom = it }, valueRange = 1f..3f, enabled = !busy, modifier = Modifier.semantics { contentDescription = "Crop zoom" })
                    Text("Horizontal position", color = p.ink2)
                    Slider(x, { x = it }, enabled = !busy, modifier = Modifier.semantics { contentDescription = "Crop horizontal position" })
                    Text("Vertical position", color = p.ink2)
                    Slider(y, { y = it }, enabled = !busy, modifier = Modifier.semantics { contentDescription = "Crop vertical position" })
                } else if (error == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (error != null) Text(error.orEmpty(), color = p.danger)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onCancel, enabled = !busy) { Text("Cancel crop") }
                    Button(enabled = image != null && !busy, onClick = {
                        busy = true; error = null
                        scope.launch {
                            try {
                                val hash = withContext(Dispatchers.IO) {
                                    val src = checkNotNull(image)
                                    val r = ProfileCrop.rect(src.width, src.height, banner, zoom, x, y)
                                    val crop = Bitmap.createBitmap(src, r.x, r.y, r.width, r.height)
                                    val maxWidth = if (banner) 1500 else 512
                                    val scale = minOf(1f, maxWidth.toFloat() / r.width)
                                    val small = Bitmap.createScaledBitmap(crop, maxOf(1, (r.width * scale).toInt()),
                                        maxOf(1, (r.height * scale).toInt()), true)
                                    try {
                                        val bytes = ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
                                        val staged = Blobs.stage(context, bytes.inputStream(), "image/png", account)
                                        val device = garden.vayne.chorus.data.Chorus.get(context).device?.takeIf { it.accountId == account }
                                            ?: error("The account changed. Reopen this profile.")
                                        // Uploads move their queue copy into the chat cache. Retain the
                                        // prepared profile image now so a cold offline open never depends
                                        // on the background keeper winning that race.
                                        check(Blobs.keep(context, staged.hash, device)) { "Could not keep this image on the device. Try again." }
                                        staged.hash
                                    } finally {
                                        if (small !== crop) small.recycle()
                                        if (crop !== src) crop.recycle()
                                    }
                                }
                                onApply(hash)
                            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                            catch (e: Exception) { error = e.message ?: "Could not prepare this image. Try again." }
                            finally { busy = false }
                        }
                    }) { Text(if (busy) "Preparing…" else "Apply crop") }
                }
                Text("Apply adds this crop to your draft. Save profile to keep it.", style = MaterialTheme.typography.bodySmall, color = p.ink3)
            }
        }
    }
}
