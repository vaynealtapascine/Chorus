package garden.vayne.chorus.ui

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.clickable
import garden.vayne.chorus.designsystem.R
import garden.vayne.chorus.data.*
import garden.vayne.chorus.designsystem.LocalChorusPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Make or edit a channel or a members' chat (D-088, D-099), or a group's name and face ([group]).
 * In our own space it also says who a channel is kept to, or who is in a members' chat. Uses
 * Android's photo picker and the same durable staged-upload path as attachments.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun ChannelEditor(chorus: Chorus, model: Model, initialSpace: String?, channel: ChatChannel?,
    onDismiss: () -> Unit, onSaved: (String, String) -> Unit, group: ChatSpace? = null, canDelete: Boolean = false) {
    val p = LocalChorusPalette.current
    val ctx = LocalContext.current
    val actions = rememberCoroutineScope()
    // Compare with the sheet's opening snapshot, never a model refreshed by another device.
    val baselineGroupName by rememberSaveable(group?.id) { mutableStateOf(group?.name.orEmpty()) }
    val baselineGroupDescription by rememberSaveable(group?.id) { mutableStateOf(group?.description) }
    val baselineIcon by rememberSaveable(channel?.id) { mutableStateOf(channel?.icon) }
    val baselineName by rememberSaveable(channel?.id) { mutableStateOf(channel?.name.orEmpty()) }
    val baselineTopic by rememberSaveable(channel?.id) { mutableStateOf(channel?.topic) }
    val baselineColour by rememberSaveable(channel?.id) { mutableStateOf(channel?.color) }
    var colour by rememberSaveable(channel?.id) { mutableStateOf(channel?.color ?: "#42A397") }
    val baselineAvatar by rememberSaveable(channel?.id) { mutableStateOf(channel?.avatarBlob) }
    val imagePrefs = remember { ctx.getSharedPreferences("chorus.server-capabilities", 0) }
    val serverBase = chorus.device?.base.orEmpty()
    var imagesSupported by remember(serverBase) { mutableStateOf(imagePrefs.getBoolean("images:$serverBase", false)) }
    val connection by chorus.status.collectAsState()
    LaunchedEffect(serverBase, connection) {
        if (connection != Status.Live) return@LaunchedEffect
        try {
            imagesSupported = ChannelCompose.supportsImages(Api.call("GET", serverBase, "/server", null, null))
            imagePrefs.edit().putBoolean("images:$serverBase", imagesSupported).apply()
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { /* Previously confirmed capabilities remain usable offline. */ }
    }
    var spaceId by rememberSaveable { mutableStateOf(channel?.spaceId ?: group?.id ?: initialSpace
        ?: model.spaces.firstOrNull { it.kind == "internal" }?.id.orEmpty()) }
    // a group edits its own name and description; its face is its one chat's
    var name by rememberSaveable { mutableStateOf(group?.name ?: channel?.name.orEmpty()) }
    var topic by rememberSaveable { mutableStateOf(group?.description ?: channel?.topic.orEmpty()) }
    var icon by rememberSaveable { mutableStateOf(channel?.icon.orEmpty()) }
    val memberChat = channel?.kind == "member_dm"
    val baselineMembers by rememberSaveable(channel?.id) { mutableStateOf(channel?.memberIds.orEmpty()) }
    var memberIds by rememberSaveable { mutableStateOf(channel?.memberIds.orEmpty()) }
    var keptToSome by rememberSaveable { mutableStateOf(channel?.memberIds?.isNotEmpty() == true) }
    var memberQuery by rememberSaveable { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    val internal = model.spaces.find { it.id == spaceId }?.kind == "internal" && group == null
    var avatar by rememberSaveable { mutableStateOf(channel?.avatarBlob) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var spacesOpen by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) actions.launch {
            busy = true; error = null
            try {
                val dev = checkNotNull(chorus.device)
                avatar = withContext(Dispatchers.IO) {
                    val bytes = ctx.contentResolver.openInputStream(uri)?.use { input ->
                        ByteArrayOutputStream().also { output ->
                            val buffer = ByteArray(16 * 1024)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                require(output.size() + count <= 10 * 1024 * 1024) { "Choose an image under 10 MB." }
                                output.write(buffer, 0, count)
                            }
                        }.toByteArray() }
                        ?: error("That image could not be opened. Choose it again.")
                    require(bytes.size <= 10 * 1024 * 1024) { "Choose an image under 10 MB." }
                    // ImageDecoder applies EXIF orientation before the square crop (minSdk 29).
                    val source = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        val scale = minOf(1f, 1024f / maxOf(info.size.width, info.size.height))
                        decoder.setTargetSize(maxOf(1, (info.size.width * scale).toInt()), maxOf(1, (info.size.height * scale).toInt()))
                    }
                    val side = minOf(source.width, source.height)
                    val crop = Bitmap.createBitmap(source, (source.width - side) / 2, (source.height - side) / 2, side, side)
                    val small = Bitmap.createScaledBitmap(crop, minOf(side, 512), minOf(side, 512), true)
                    val output = ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
                    val staged = Blobs.stage(ctx, output.inputStream(), "image/png", dev.accountId)
                    if (small !== crop) small.recycle()
                    if (crop !== source) crop.recycle()
                    source.recycle()
                    staged.hash
                }
            } catch (e: Exception) { error = e.message ?: "Could not use this image. Try another." }
            finally { busy = false }
        }
    }
    val kept = if (internal && (memberChat || keptToSome)) memberIds else emptyList()
    val problem = when {
        memberChat && internal && kept.size < 2 -> "A members' chat needs at least two members."
        !memberChat && internal && keptToSome && kept.isEmpty() -> "Choose who it is kept to."
        else -> null
    }
    fun save() {
        busy = true; error = null
        actions.launch {
            try {
                val id = channel?.id ?: group?.id ?: chorus.newId()
                if (group != null) {
                    val changes = ChatManage.spaceChanges(group.copy(name = baselineGroupName, description = baselineGroupDescription), name, topic)
                    if (changes.length() > 0) chorus.create("space.set", group.id, changes, scope = "space:${group.id}")
                    // the group's face is its one chat's: only the picture, icon and colour
                    if (channel != null) {
                        val look = ChannelCompose.update(channel.copy(avatarBlob = baselineAvatar, color = baselineColour, icon = baselineIcon),
                            channel.name, channel.topic.orEmpty(), avatar, colour, icon)
                        if (look.length() > 0) chorus.create("channel.set", channel.id, look, scope = "space:${group.id}")
                    }
                } else {
                    val payload = if (channel == null) ChannelCompose.create(spaceId, name, topic, avatar, colour, icon, kept)
                        else ChannelCompose.update(channel.copy(name = baselineName, topic = baselineTopic, avatarBlob = baselineAvatar,
                            color = baselineColour, memberIds = baselineMembers, icon = baselineIcon), name, topic, avatar, colour, icon,
                            if (internal) kept else channel.memberIds)
                    if (payload.length() > 0) chorus.create(if (channel == null) "channel.create" else "channel.set",
                        id, payload, scope = "space:$spaceId")
                }
                if (avatar != null) UploadWork.enqueue(ctx)
                onSaved(spaceId, id)
            } catch (e: Exception) { error = e.message ?: "Could not save this channel." }
            finally { busy = false }
        }
    }
    val title = when { group != null -> "Edit group"; channel == null -> "New channel"; memberChat -> "Edit chat"; else -> "Edit channel" }
    Drawer(onDismiss, title, if (connection == Status.Live) null else "Offline", dismissible = !busy,
        footer = {
            if (problem != null) Text(problem, fontSize = 13.sp, color = p.ink3)
            DrawerButton(if (channel == null) "Create channel" else "Save", ::save, busy = busy,
                enabled = problem == null && name.isNotBlank() && name.trim().length <= 80 && spaceId.isNotBlank())
            if (channel != null && canDelete && group == null) DrawerButton(if (memberChat) "Delete chat" else "Delete channel",
                { confirmDelete = true }, ButtonKind.Quiet, enabled = !busy)
        }) {
        if (group == null || channel != null) FormCard {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ChannelDisc(ChatChannel("preview", spaceId, "text", name.ifBlank { "Channel" }, null,
                    avatarBlob = avatar, icon = icon.trim().ifEmpty { null }, color = colour), size = 64.dp)
                Column(Modifier.weight(1f)) {
                    Text(if (avatar == null) "Add a picture" else "Change picture", color = if (imagesSupported && !busy) p.accent else p.ink3,
                        fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable(enabled = !busy && imagesSupported) { picker.launch("image/*") }.padding(vertical = 6.dp))
                    if (avatar != null) Text("Remove picture", color = p.ink2, fontSize = 14.sp,
                        modifier = Modifier.clickable(enabled = !busy && imagesSupported) { avatar = null }.padding(vertical = 6.dp))
                }
            }
        }
        if (channel == null && group == null) {
            val spaces = model.spaces.filter { it.kind != "dm" }
            ChoiceField("Space", model.spaces.find { it.id == spaceId }?.name ?: "Choose a space", enabled = !busy) { spacesOpen = true }
            ChoiceDrawer(spacesOpen, "Which space", spaces, spaces.find { it.id == spaceId }, { it.name },
                { spaceId = it.id; spacesOpen = false }, { spacesOpen = false })
        }
        ChorusField(name, { name = it }, label = { Text(when { group != null -> "Group name"; memberChat -> "Chat name"; else -> "Channel name" }) },
            singleLine = true, enabled = !busy, isError = name.length > 80, modifier = Modifier.fillMaxWidth())
        ChorusField(topic, { topic = it }, label = { Text("Description (optional)") },
            enabled = !busy, minLines = 2, modifier = Modifier.fillMaxWidth())
        if (group == null || channel != null) {
            ChorusField(icon, { icon = it.take(16) }, label = { Text("Icon (an emoji, optional)") }, singleLine = true,
                enabled = !busy, modifier = Modifier.fillMaxWidth())
            ColourField(colour, { colour = it }, modifier = Modifier.fillMaxWidth(), label = "Colour", enabled = !busy)
        }
        if (internal && !model.isPerson) {
            if (!memberChat) {
                SectionLabel("Who it's for")
                Segmented(listOf("Everyone", "Only some members"), if (keptToSome) 1 else 0) { if (!busy) keptToSome = it == 1 }
            }
            if (memberChat || keptToSome) {
                if (memberChat) SectionLabel("People in this chat")
                val people = model.active.filter { m -> memberQuery.isBlank() ||
                    listOf(m.shownName, m.name, *m.sigils.toTypedArray()).any { it.contains(memberQuery.trim(), true) } }
                if (model.active.size > 8) ChorusField(memberQuery, { memberQuery = it }, label = { Text("Search members") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                FormCard {
                    people.take(60).forEachIndexed { i, m ->
                        if (i > 0) FormDivider()
                        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp)
                            .toggleable(m.id in memberIds, enabled = !busy, role = androidx.compose.ui.semantics.Role.Checkbox) { on ->
                                memberIds = if (on) (memberIds + m.id).distinct() else memberIds - m.id
                            }.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Avatar(m.glyph, m.color, 32.dp, avatarBlob = m.avatarBlob, ring = false)
                            Text(m.shownName, color = p.ink, modifier = Modifier.weight(1f))
                            Checkbox(m.id in memberIds, onCheckedChange = null)
                        }
                    }
                }
                if (people.size > 60) Text("Search to find more members.", fontSize = 13.sp, color = p.ink3)
            }
        }
        if (error != null) FormNote(error.orEmpty(), warning = true)
        if (confirmDelete && channel != null) ConfirmDrawer(if (memberChat) "Delete this chat?" else "Delete ${channel.name}?",
            null, "Delete", danger = true,
            onDismiss = { confirmDelete = false }, onConfirm = {
                confirmDelete = false
                actions.launch {
                    try { chorus.create("channel.delete", channel.id, org.json.JSONObject(), scope = "space:${channel.spaceId}"); onDismiss() }
                    catch (e: Exception) { error = e.message ?: "Could not delete it." }
                }
            })
    }
}
