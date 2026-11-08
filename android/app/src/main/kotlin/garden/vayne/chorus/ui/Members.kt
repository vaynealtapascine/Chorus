package garden.vayne.chorus.ui

import androidx.compose.animation.core.animateFloatAsState
import garden.vayne.chorus.designsystem.R
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import garden.vayne.chorus.InspectionDialogContent
import garden.vayne.chorus.data.Blobs
import garden.vayne.chorus.data.Chorus
import garden.vayne.chorus.data.Member
import garden.vayne.chorus.data.MemberFolder
import garden.vayne.chorus.data.MemberListPrefs
import garden.vayne.chorus.data.MemberOrdering
import garden.vayne.chorus.data.Model
import garden.vayne.chorus.data.Subsystems
import garden.vayne.chorus.designsystem.LocalChorusPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val ALL = "all"
private const val ARCHIVED = "archived"

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun Members(chorus: Chorus, model: Model, onJournal: (String?, String?) -> Unit,
    active: Boolean = true, onFocusedRoute: (Boolean) -> Unit = {}) {
    val p = LocalChorusPalette.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var adding by rememberSaveable { mutableStateOf(false) }
    var profile by rememberSaveable { mutableStateOf<String?>(null) }
    // All · a group · Archived (as on web)
    var filter by rememberSaveable { mutableStateOf(ALL) }
    var reordering by rememberSaveable { mutableStateOf(false) }
    var customizing by rememberSaveable { mutableStateOf(false) }
    var managingGroups by rememberSaveable { mutableStateOf(false) }
    var importing by rememberSaveable { mutableStateOf(false) }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var picked by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var rolesFor by rememberSaveable { mutableStateOf<String?>(null) }
    // posts opened from a profile, newline-separated: the last is shown; back returns to the one before
    var postTrail by rememberSaveable { mutableStateOf("") }
    val openPosts = postTrail.split('\n').filter { it.isNotEmpty() }
    // members a new folder is being made for (empty: just make the folder)
    var newFolderFor by rememberSaveable { mutableStateOf<List<String>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    SideEffect { if (active) onFocusedRoute(adding || profile != null || reordering || openPosts.isNotEmpty()) }
    BackHandler(active && (profile != null || adding || reordering || selecting || openPosts.isNotEmpty())) {
        when { openPosts.isNotEmpty() -> postTrail = openPosts.dropLast(1).joinToString("\n")
            adding -> adding = false; reordering -> reordering = false; selecting -> { selecting = false; picked = emptyList() }; else -> profile = null }
    }
    if (adding) { MemberEditor(chorus, model, null) { adding = false }; return }
    if (reordering) { MemberReorder(chorus, model) { reordering = false }; return }
    openPosts.lastOrNull()?.let { id ->
        JournalThread(chorus, model, id, onClose = { postTrail = openPosts.dropLast(1).joinToString("\n") },
            onReply = { onJournal(null, id) }, onOpenPost = { next -> postTrail = (openPosts + next).joinToString("\n") })
        return
    }
    profile?.let { id ->
        MemberProfile(chorus, model, id, onClose = { profile = null }, onWrite = { onJournal(id, null) },
            onReply = { onJournal(null, it) }, closeLabel = "Members", writeLabel = "Journal", onOpenProfile = { profile = it },
            onOpenPost = { postTrail = it })
        return
    }
    val settings = model.memberList
    val view = settings.view
    fun write(failure: String, block: suspend () -> Unit) {
        scope.launch {
            try { block(); error = null }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { error = failure }
        }
    }
    fun pref(payload: JSONObject, failure: String = "Could not save that. Try again.") = write(failure) { chorus.create("pref.set", null, payload) }
    fun file(ids: List<String>, folder: String?) = write("Could not move them. Try again.") {
        for (id in ids) if (settings.folderOf[id] != folder) chorus.create("pref.set", null, MemberListPrefs.filePayload(id, folder))
    }

    // folded-up folders are this device's own choice
    val local = remember { ctx.getSharedPreferences("chorus.members", 0) }
    var collapsed by remember { mutableStateOf(local.getStringSet("collapsed", emptySet()).orEmpty()) }
    fun toggleCollapsed(id: String) {
        collapsed = if (id in collapsed) collapsed - id else collapsed + id
        local.edit().putStringSet("collapsed", collapsed).apply()
    }

    val archived = filter == ARCHIVED
    val eligible = remember(model.members, chorus.device?.accountId, archived) {
        MemberOrdering.eligible(model.members, chorus.device?.accountId, archived)
    }
    val filtered = if (filter == ALL || archived) eligible else eligible.filter { model.membership[filter]?.contains(it.id) == true }
    val searched = if (query.isBlank()) null else filtered.mapNotNull { m ->
        fuzzy(query, listOfNotNull(m.name, m.displayName, m.pronouns, *m.sigils.toTypedArray(), *settings.roles(m.id).toTypedArray()).joinToString(" "))?.let { it to m }
    }.sortedWith(compareBy<Pair<Int, Member>> { it.first }
        .thenBy { it.second.shownName.lowercase(java.util.Locale.ROOT) }.thenBy { it.second.id }).map { it.second }
    // folders only for the plain list of active members; searches, Archived and the flat view are one list
    val foldered = !view.flat && searched == null && !archived
    val sections = if (foldered) MemberListPrefs.sections(filtered, settings.folders, settings.folderOf)
        .filter { s -> if (s.folder != null) filter == ALL || s.members.isNotEmpty() else s.members.isNotEmpty() || settings.folders.isEmpty() }
        else listOf(garden.vayne.chorus.data.MemberSection(null, searched ?: MemberOrdering.ordered(filtered)))
    val shownCount = sections.sumOf { it.members.size }
    val list = rememberLazyListState()
    // the front as switched (D-102)
    val here = model.current

    Box(Modifier.fillMaxSize()) {
        val motion = rememberChorusMotion()
        LazyColumn(Modifier.padding(horizontal = 16.dp), state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    ChorusField(query, { query = it }, label = { Text("Search members") },
                        placeholder = { Text("${eligible.size} members") }, singleLine = true, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { adding = true }) { Text("Add") }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    SingleChoiceSegmentedButtonRow {
                        SegmentedButton(!view.flat, { pref(MemberListPrefs.viewPayload(view.copy(flat = false))) }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Folders") }
                        SegmentedButton(view.flat, { pref(MemberListPrefs.viewPayload(view.copy(flat = true))) }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("List") }
                    }
                    if (foldered) FilterChip(selecting, { selecting = !selecting; if (!selecting) picked = emptyList() }, label = { Text(if (selecting) "Done" else "Select") })
                    FilterChip(false, { customizing = true }, label = { Text("Customize") })
                    TextButton(enabled = !archived && query.isBlank() && eligible.size > 1, onClick = { reordering = true }) { Text("Reorder") }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(filter == ALL, { filter = ALL }, label = { Text("All") })
                    for (g in model.groups) FilterChip(filter == g.id, { filter = g.id }, label = { Text(g.name) })
                    FilterChip(archived, { filter = if (archived) ALL else ARCHIVED }, label = { Text("Archived") })
                    TextButton(onClick = { managingGroups = true }) { Text("Groups…") }
                    TextButton(onClick = { importing = true }) { Text("Import from PluralKit…") }
                }
                error?.let { Text(it, color = p.danger, modifier = Modifier.padding(vertical = 4.dp)) }
                if (shownCount == 0 && !(foldered && filter == ALL && settings.folders.isNotEmpty()))
                    EmptyState(if (query.isNotBlank()) R.drawable.ic_search else if (archived) R.drawable.ic_archive else R.drawable.ic_members,
                        if (query.isNotBlank()) "No matches" else "None yet")
            }
            for (s in sections) {
                val folder = s.folder
                val open = folder == null || folder.id !in collapsed
                if (foldered && (folder != null || settings.folders.isNotEmpty())) item(key = "folder:${folder?.id ?: ""}") {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .then(if (folder != null) Modifier.clickable { toggleCollapsed(folder.id) } else Modifier)
                        .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val turn by animateFloatAsState(if (open) 90f else 0f, ChorusMotion.tween(motion, ChorusMotion.Quick), label = "folder chevron")
                        if (folder != null) ChorusIcon(AppIcon.Chevron, if (open) "Fold up ${folder.name}" else "Open ${folder.name}",
                            Modifier.size(16.dp).rotate(turn))
                        Text(folder?.name ?: "Not in a folder", color = if (folder != null) p.ink2 else p.ink3, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        Text("${s.members.size}", color = p.ink3, style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (!open) continue
                if (folder != null && s.members.isEmpty()) item(key = "empty:${folder.id}") {
                    Text("Empty", color = p.ink3, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth().border(1.dp, p.line, RoundedCornerShape(12.dp)).padding(12.dp))
                }
                items(s.members, key = { it.id }) { m ->
                    // opening a folder fades its members in; moving one glides it
                    Box(Modifier.animateItem(fadeInSpec = if (motion) ChorusMotion.tween(true) else null,
                        placementSpec = if (motion) ChorusMotion.settle() else null,
                        fadeOutSpec = if (motion) ChorusMotion.tween(true, ChorusMotion.Quick) else null)) {
                    MemberRow(chorus, model, m, here.find { it.subjectId == m.id && it.subjectType == "member" }?.level,
                        selecting = selecting, picked = m.id in picked, foldered = foldered,
                        onOpen = { if (selecting) picked = if (m.id in picked) picked - m.id else picked + m.id else profile = m.id },
                        onMove = { file(listOf(m.id), it) }, onNewFolder = { newFolderFor = listOf(m.id) },
                        onRoles = { rolesFor = m.id }, onSelect = { selecting = true; picked = listOf(m.id) })
                    }
                }
            }
            item { Spacer(Modifier.height(if (selecting) 96.dp else 24.dp)) }
        }
        if (selecting) Surface(Modifier.align(Alignment.BottomCenter).padding(16.dp), shape = RoundedCornerShape(50), color = p.surface2, shadowElevation = 6.dp) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${picked.size} selected", color = p.ink)
                var moving by remember { mutableStateOf(false) }
                Box {
                    Button(enabled = picked.isNotEmpty(), onClick = { moving = true }) { Text("Move to…") }
                    FolderMenu(moving, settings.folders, null, onDismiss = { moving = false }, onMove = {
                        file(picked, it); moving = false; selecting = false; picked = emptyList()
                    }, onNewFolder = { moving = false; newFolderFor = picked })
                }
                TextButton(onClick = { selecting = false; picked = emptyList() }) { Text("Cancel") }
            }
        }
    }

    if (customizing) MembersCustomizeSheet(chorus, model, onError = { error = it }, onNewFolder = { newFolderFor = emptyList() }) { customizing = false }
    if (managingGroups) GroupsSheet(chorus, model) { managingGroups = false }
    if (importing) PkImportSheet(chorus) { importing = false }
    rolesFor?.let { id -> model.member(id)?.let { RolesDialog(chorus, model, it) { rolesFor = null } } ?: run { rolesFor = null } }
    newFolderFor?.let { ids ->
        var name by remember { mutableStateOf("") }
        Drawer({ newFolderFor = null }, "New folder", if (ids.isEmpty()) null else "With ${ids.size} ${if (ids.size == 1) "member" else "members"}", footer = {
            DrawerButton(if (ids.isEmpty()) "Add folder" else "Add and move", enabled = name.isNotBlank(), onClick = {
                val id = chorus.newId()
                val folders = MemberListPrefs.addFolder(settings.folders, id, name)
                write("Could not make the folder. Try again.") {
                    chorus.create("pref.set", null, MemberListPrefs.foldersPayload(folders))
                    for (m in ids) chorus.create("pref.set", null, MemberListPrefs.filePayload(m, id))
                }
                if (ids.isNotEmpty()) { selecting = false; picked = emptyList() }
                newFolderFor = null
            })
        }) {
            ChorusField(name, { name = it }, label = { Text("Name") }, placeholder = { Text("e.g. Littles") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
private fun MemberRow(chorus: Chorus, model: Model, m: Member, level: String?, selecting: Boolean, picked: Boolean, foldered: Boolean,
    onOpen: () -> Unit, onMove: (String?) -> Unit, onNewFolder: () -> Unit, onRoles: () -> Unit, onSelect: () -> Unit) {
    val p = LocalChorusPalette.current
    val settings = model.memberList
    val view = settings.view
    var menu by remember { mutableStateOf(false) }
    val banner = m.bannerBlob?.takeIf { view.banners }
    val shadow = if (banner != null) TextStyle(shadow = Shadow(p.surface, blurRadius = 8f)) else TextStyle.Default
    Box {
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.surface)
            .then(if (picked) Modifier.border(2.dp, p.accent, RoundedCornerShape(14.dp)) else Modifier)
            .combinedClickable(onClick = onOpen, onLongClick = { if (!selecting) menu = true })) {
            if (banner != null) Box(Modifier.matchParentSize(), contentAlignment = Alignment.BottomEnd) { RowBanner(chorus, banner) }
            Row(Modifier.fillMaxWidth().heightIn(min = 76.dp).padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (selecting) Checkbox(picked, { onOpen() })
                Avatar(m.glyph, m.color, 44.dp, avatarBlob = m.avatarBlob)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // inside a member who is a subsystem: "in Foxglove" rather than the group's name
                    val inside = Subsystems.parts(model, m.id).size.takeIf { Subsystems.groupOf(model, m.id) != null }
                    val within = model.membership.filter { m.id in it.value }.keys.firstNotNullOfOrNull { id ->
                        Subsystems.memberOf(model, id)?.takeIf { it.id != m.id }?.let { "in ${it.shownName}" } }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(m.shownName, color = tonesOf(m.color).name, fontWeight = FontWeight.SemiBold, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, style = shadow, modifier = Modifier.widthIn(max = 220.dp))
                        // the name keeps its room; pronouns and subsystem notes give way first
                        val sub = listOfNotNull(m.pronouns, inside?.let { "Subsystem · $it inside" }, within).joinToString(" · ")
                        if (sub.isNotEmpty()) Text(sub, color = p.ink3, style = MaterialTheme.typography.bodySmall.merge(shadow), maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    }
                    val pills = settings.pills(m.id)
                    if (pills.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (r in pills) RolePill(r)
                    }
                    if (view.bio) MemberListPrefs.bioLine(m.description)?.let {
                        Text(it, color = p.ink2, style = MaterialTheme.typography.bodySmall.merge(shadow), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                level?.let {
                    Surface(shape = RoundedCornerShape(50), color = p.surface.copy(alpha = .8f), modifier = Modifier.padding(start = 8.dp)) {
                        Text(if (it == "front") "Fronting" else if (it == "cocon") "Co-conscious" else "Present",
                            color = p.accent, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                    }
                }
                if (!selecting) ChorusIcon(AppIcon.Chevron, modifier = Modifier.padding(start = 8.dp))
            }
        }
        MenuDrawer(menu, { menu = false }, m.shownName, m.pronouns, leading = { Avatar(m.glyph, m.color, 40.dp, avatarBlob = m.avatarBlob, ring = false) }) {
            MenuRow("Roles…", R.drawable.ic_members) { menu = false; onRoles() }
            if (foldered) MenuRow("Select several", R.drawable.ic_check) { menu = false; onSelect() }
            if (foldered) {
                SectionLabel("Move to")
                for (f in settings.folders) MenuRow(f.name, R.drawable.ic_folder, selected = settings.folderOf[m.id] == f.id) { menu = false; onMove(f.id) }
                MenuRow("Not in a folder", selected = m.id !in settings.folderOf) { menu = false; onMove(null) }
                MenuRow("New folder…", R.drawable.ic_plus) { menu = false; onNewFolder() }
            }
        }
    }
}

/** The whole banner at the row's height, anchored bottom-right, fading out towards the name (D-100). */
@Composable
internal fun RowBanner(chorus: Chorus, hash: String) {
    val ctx = LocalContext.current
    val dev = chorus.device
    val image by produceState<android.graphics.Bitmap?>(null, hash, dev?.session) {
        value = withContext(Dispatchers.IO) { runCatching { Blobs.image(ctx, hash, dev, kind = "profile", maxPx = 900, maxBytes = 10L * 1024 * 1024) }.getOrNull() }
    }
    val shown = image ?: return
    Image(shown.asImageBitmap(), null, contentScale = ContentScale.Fit, alignment = Alignment.BottomEnd,
        modifier = Modifier.fillMaxHeight().aspectRatio(shown.width.toFloat() / shown.height.coerceAtLeast(1), matchHeightConstraintsFirst = true)
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            .drawWithContent {
                drawContent()
                drawRect(Brush.horizontalGradient(0f to Color.Transparent, .4f to Color.Black.copy(alpha = .45f), .85f to Color.Black), blendMode = BlendMode.DstIn)
            })
}

@Composable
internal fun RolePill(role: String, on: Boolean = true, onClick: (() -> Unit)? = null) {
    val p = LocalChorusPalette.current
    val c = runCatching { Color(android.graphics.Color.parseColor(MemberListPrefs.roleColor(role))) }.getOrDefault(p.accent)
    val shape = RoundedCornerShape(50)
    Text(role, color = if (on) p.ink else p.ink2, style = MaterialTheme.typography.labelSmall, fontWeight = if (on) FontWeight.SemiBold else null, maxLines = 1,
        modifier = Modifier.clip(shape).then(if (on) Modifier.background(c.copy(alpha = .28f).compositeOver(p.surface)).border(1.dp, c.copy(alpha = .55f), shape)
            else Modifier.border(1.dp, p.line, shape)).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = if (onClick != null) 12.dp else 8.dp, vertical = if (onClick != null) 6.dp else 1.dp))
}

@Composable
private fun FolderMenu(open: Boolean, folders: List<MemberFolder>, current: String?, onDismiss: () -> Unit, onMove: (String?) -> Unit, onNewFolder: () -> Unit) {
    MenuDrawer(open, onDismiss, "Move to") {
        for (f in folders) MenuRow(f.name, R.drawable.ic_folder, selected = current == f.id) { onMove(f.id) }
        MenuRow("Not in a folder", selected = current == null) { onMove(null) }
        MenuRow("New folder…", R.drawable.ic_plus, onClick = onNewFolder)
    }
}
