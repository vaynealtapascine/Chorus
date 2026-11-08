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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import garden.vayne.chorus.data.Blobs
import garden.vayne.chorus.data.Chorus
import garden.vayne.chorus.data.Member
import garden.vayne.chorus.data.MemberDirectory
import garden.vayne.chorus.data.MemberFolder
import garden.vayne.chorus.data.MemberListPrefs
import garden.vayne.chorus.data.MemberOrdering
import garden.vayne.chorus.data.Model
import garden.vayne.chorus.data.ProfilePresentation
import garden.vayne.chorus.data.Subsystems
import garden.vayne.chorus.designsystem.LocalChorusPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val ALL = "all"
private const val FRONTING = "fronting"
private const val ARCHIVED = "archived"

/**
 * Members (UI21, Figma F4.1, F4.4): a title row with the system and a count, tabs Members ·
 * Groups · Custom fronts, counted chips, flat rows with the member's front layer as a pill, then
 * the groups and the archived members. Everything about how the list looks (D-100) is behind the
 * sliders icon. A member opens on their profile; + makes a member, group or custom front.
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun Members(chorus: Chorus, model: Model, onJournal: (String?, String?) -> Unit,
    active: Boolean = true, onFocusedRoute: (Boolean) -> Unit = {}) {
    val p = LocalChorusPalette.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var creatingGroup by rememberSaveable { mutableStateOf(false) }
    var creatingState by rememberSaveable { mutableStateOf(false) }
    var profile by rememberSaveable { mutableStateOf<String?>(null) }
    // All · Fronting · Archived · a group (opened from the Groups rows)
    var filter by rememberSaveable { mutableStateOf(ALL) }
    var reordering by rememberSaveable { mutableStateOf(false) }
    var viewing by rememberSaveable { mutableStateOf(false) }
    var importing by rememberSaveable { mutableStateOf(false) }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var picked by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var rolesFor by rememberSaveable { mutableStateOf<String?>(null) }
    var groupsFor by rememberSaveable { mutableStateOf<String?>(null) }
    // posts opened from a profile, newline-separated: the last is shown; back returns to the one before
    var postTrail by rememberSaveable { mutableStateOf("") }
    val openPosts = postTrail.split('\n').filter { it.isNotEmpty() }
    // members a new folder is being made for (empty: just make the folder)
    var newFolderFor by rememberSaveable { mutableStateOf<List<String>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    SideEffect { if (active) onFocusedRoute(adding || profile != null || reordering || openPosts.isNotEmpty()) }
    BackHandler(active && (profile != null || adding || reordering || selecting || openPosts.isNotEmpty() || searching || filter != ALL)) {
        when { openPosts.isNotEmpty() -> postTrail = openPosts.dropLast(1).joinToString("\n")
            adding -> adding = false; reordering -> reordering = false; selecting -> { selecting = false; picked = emptyList() }
            profile != null -> profile = null; searching -> { searching = false; query = "" }; else -> filter = ALL }
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

    val account = chorus.device?.accountId
    // a group filter whose group has gone falls back to everyone
    if (filter != ALL && filter != FRONTING && filter != ARCHIVED && model.group(filter) == null) filter = ALL
    val archived = filter == ARCHIVED
    val counts = remember(model.members, model.current, account) { MemberDirectory.counts(model, account) }
    val fronting = remember(model.current) { MemberDirectory.frontingIds(model) }
    // the front layer's own name for the pill ("Steering"), for those in front only
    val layers = remember(model.current, model.namedFronts, model.frontIntervals) {
        fronting.mapNotNull { id -> ProfilePresentation.frontStatus(model, id)?.let { id to it.layer } }.toMap()
    }
    val eligible = remember(model.members, account, archived) { MemberOrdering.eligible(model.members, account, archived) }
    val filtered = when (filter) {
        ALL, ARCHIVED -> eligible
        FRONTING -> eligible.filter { it.id in fronting }
        else -> eligible.filter { model.membership[filter]?.contains(it.id) == true }
    }
    val searched = if (query.isBlank() || tab != 0) null else filtered.mapNotNull { m ->
        fuzzy(query, listOfNotNull(m.name, m.displayName, m.pronouns, *m.sigils.toTypedArray(), *settings.roles(m.id).toTypedArray()).joinToString(" "))?.let { it to m }
    }.sortedWith(compareBy<Pair<Int, Member>> { it.first }
        .thenBy { it.second.shownName.lowercase(java.util.Locale.ROOT) }.thenBy { it.second.id }).map { it.second }
    // folders only for the plain list of everyone active; searches, filters and the flat view are one list
    val foldered = !view.flat && searched == null && filter == ALL
    val sections = if (foldered) MemberListPrefs.sections(filtered, settings.folders, settings.folderOf)
        .filter { s -> s.folder != null || s.members.isNotEmpty() || settings.folders.isEmpty() }
        else listOf(garden.vayne.chorus.data.MemberSection(null, searched ?: MemberOrdering.ordered(filtered)))
    val shownCount = sections.sumOf { it.members.size }
    val list = rememberLazyListState()
    val systemName = model.systemName?.takeIf { it.isNotBlank() }

    Column(Modifier.fillMaxSize().background(p.bg)) {
        val noun = when (tab) { 1 -> "group" to model.groups.size; 2 -> "custom front" to model.states.size; else -> "member" to counts.all }
        MembersTitleRow(inboxReader(model, null), listOfNotNull(systemName, "${noun.second} ${noun.first}${if (noun.second == 1) "" else "s"}").joinToString(" · "),
            searching, onSearch = { searching = !searching; if (!searching) query = "" },
            onAdd = { when (tab) { 1 -> creatingGroup = true; 2 -> creatingState = true; else -> adding = true } }, addLabel = "New ${noun.first}")
        UnderlineTabs(listOf("Members" to 0, "Groups" to 0, "Custom fronts" to 0), tab) { tab = it; selecting = false; picked = emptyList() }
        if (searching) Box(Modifier.fillMaxWidth().background(p.surface).padding(horizontal = 16.dp, vertical = 8.dp)) {
            ChorusField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text(when (tab) { 1 -> "Search groups"; 2 -> "Search custom fronts"; else -> "Search members" }) })
        }
        error?.let { Text(it, color = p.danger, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
        // no labelled returns out of the inline Column: the release dexer can't take them (NOTES, Android)
        if (tab == 1) GroupsTab(chorus, model, query, creatingGroup, { creatingGroup = it }, onOpen = { profile = it })
        else if (tab == 2) CustomFrontsTab(chorus, model, query, creatingState, { creatingState = it })
        else Box(Modifier.fillMaxSize()) {
        val motion = rememberChorusMotion()
        LazyColumn(Modifier.fillMaxSize(), state = list) {
            item(key = "chips") {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CountChip("All", counts.all, filter == ALL) { filter = ALL }
                        if (counts.fronting > 0 || filter == FRONTING) CountChip("Fronting", counts.fronting, filter == FRONTING) { filter = if (filter == FRONTING) ALL else FRONTING }
                        if (counts.archived > 0 || archived) CountChip("Archived", counts.archived, archived) { filter = if (archived) ALL else ARCHIVED }
                        model.group(filter)?.let { g -> CountChip(g.name, filtered.size, true) { filter = ALL } }
                    }
                    BarIcon(R.drawable.ic_sliders, "How the list looks", p.ink2) { viewing = true }
                }
            }
            if (shownCount == 0 && !(foldered && settings.folders.isNotEmpty())) item(key = "empty") {
                EmptyState(if (query.isNotBlank()) R.drawable.ic_search else if (archived) R.drawable.ic_archive else R.drawable.ic_members,
                    if (query.isNotBlank()) "No matches" else "None yet")
            }
            for (s in sections) {
                val folder = s.folder
                val open = folder == null || folder.id !in collapsed
                if (foldered && (folder != null || settings.folders.isNotEmpty())) item(key = "folder:${folder?.id ?: ""}") {
                    Row(Modifier.fillMaxWidth().then(if (folder != null) Modifier.clickable { toggleCollapsed(folder.id) } else Modifier)
                        .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val turn by animateFloatAsState(if (open) 90f else 0f, ChorusMotion.tween(motion, ChorusMotion.Quick), label = "folder chevron")
                        Text(folder?.name ?: "Not in a folder", color = p.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        Text("${s.members.size}", color = p.ink3, fontSize = 12.sp)
                        Spacer(Modifier.weight(1f))
                        if (folder != null) ChorusIcon(AppIcon.Chevron, if (open) "Fold up ${folder.name}" else "Open ${folder.name}",
                            Modifier.size(16.dp).rotate(turn))
                    }
                } else if (s.members.isNotEmpty()) item(key = "label") {
                    Text(when { query.isNotBlank() -> "Results"; archived -> "Archived"; filter == FRONTING -> "Fronting"
                        else -> model.group(filter)?.let(model::groupPath) ?: "All members" },
                        color = p.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp))
                }
                if (!open) continue
                if (folder != null && s.members.isEmpty()) item(key = "empty:${folder.id}") {
                    Text("Empty", color = p.ink3, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }
                items(s.members, key = { it.id }) { m ->
                    // opening a folder fades its members in; moving one glides it
                    Box(Modifier.animateItem(fadeInSpec = if (motion) ChorusMotion.tween(true) else null,
                        placementSpec = if (motion) ChorusMotion.settle() else null,
                        fadeOutSpec = if (motion) ChorusMotion.tween(true, ChorusMotion.Quick) else null)) {
                    MemberRow(chorus, model, m, layers[m.id],
                        selecting = selecting, picked = m.id in picked, foldered = foldered,
                        onOpen = { if (selecting) picked = if (m.id in picked) picked - m.id else picked + m.id else profile = m.id },
                        onMove = { file(listOf(m.id), it) }, onNewFolder = { newFolderFor = listOf(m.id) },
                        onRoles = { rolesFor = m.id }, onGroups = { groupsFor = m.id }, onSelect = { selecting = true; picked = listOf(m.id) },
                        onRestore = { write("Could not restore ${m.shownName}. Try again.") { chorus.create("member.unarchive", m.id, JSONObject()) } })
                    }
                }
            }
            // F4.1's foot: the groups (a tap shows who is in one) and the archived members
            if (filter == ALL && query.isBlank() && !selecting) {
                if (model.groups.isNotEmpty()) item(key = "groups") {
                    SectionLabel("Groups", Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp)) {
                        Text("Manage", color = p.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) { tab = 1 }.padding(horizontal = 8.dp, vertical = 4.dp))
                    }
                }
                items(model.groups, key = { "group:${it.id}" }) { g ->
                    val inside = MemberDirectory.members(model, g.id)
                    TileRow({ GroupTile(g, 40.dp) }, model.groupPath(g), inside.takeIf { it.isNotEmpty() }?.let(MemberDirectory::names), inside.size) { filter = g.id }
                }
                if (counts.archived > 0) item(key = "archived") {
                    if (model.groups.isEmpty()) Spacer(Modifier.height(8.dp))
                    TileRow({ IconTile(R.drawable.ic_history, null, 40.dp) }, "Archived members",
                        "${counts.archived} ${if (counts.archived == 1) "member" else "members"}", null) { filter = ARCHIVED }
                }
            }
            item { Spacer(Modifier.height(if (selecting) 96.dp else 24.dp)) }
        }
        if (selecting) Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(16.dp)).background(p.surface2)
            .heightIn(min = 56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BarIcon(R.drawable.ic_close, "Stop selecting", p.ink) { selecting = false; picked = emptyList() }
            Text("${picked.size} selected", color = p.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            var moving by remember { mutableStateOf(false) }
            SmallButton("Move", primary = true, icon = R.drawable.ic_folder, enabled = picked.isNotEmpty()) { moving = true }
            FolderMenu(moving, settings.folders, null, onDismiss = { moving = false }, onMove = {
                file(picked, it); moving = false; selecting = false; picked = emptyList()
            }, onNewFolder = { moving = false; newFolderFor = picked })
        }
        }
    }

    if (viewing) MembersViewDrawer(chorus, model, onError = { error = it }, onNewFolder = { newFolderFor = emptyList() },
        onSelect = if (foldered) ({ viewing = false; selecting = true; picked = emptyList() }) else null,
        onReorder = if (counts.all > 1) ({ viewing = false; reordering = true }) else null,
        onImport = { viewing = false; importing = true }) { viewing = false }
    if (importing) PkImportSheet(chorus) { importing = false }
    rolesFor?.let { id -> model.member(id)?.let { RolesDialog(chorus, model, it) { rolesFor = null } } ?: run { rolesFor = null } }
    groupsFor?.let { id -> model.member(id)?.let { MemberGroupsDrawer(chorus, model, it) { groupsFor = null } } ?: run { groupsFor = null } }
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

/** F4.1's title row: who's reading, Members, the system and a count; search and +. */
@Composable
private fun MembersTitleRow(reader: Member?, subtitle: String, searching: Boolean, onSearch: () -> Unit, onAdd: () -> Unit, addLabel: String) {
    val p = LocalChorusPalette.current
    Row(Modifier.fillMaxWidth().background(p.surface).heightIn(min = 56.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        reader?.let { ChatAvatar(it, 36.dp); Spacer(Modifier.width(12.dp)) }
        Column(Modifier.weight(1f)) {
            Text("Members", color = p.ink, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = p.ink3, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        BarIcon(if (searching) R.drawable.ic_close else R.drawable.ic_search, if (searching) "Close search" else "Search", p.ink, onClick = onSearch)
        BarIcon(R.drawable.ic_plus, addLabel, p.ink, onClick = onAdd)
    }
}

/** A filter chip with its count (F4.1 "All 4", "Fronting 2"). */
@Composable
internal fun CountChip(label: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    val p = LocalChorusPalette.current
    Row(Modifier.clip(CircleShape).background(if (selected) p.accentSoft else p.surface2).clickable(role = Role.Tab, onClick = onClick)
        .heightIn(min = 32.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = if (selected) p.accent else p.ink, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        Text("$count", color = if (selected) p.accent else p.ink3, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

/** A teal pill at a row's end: the layer a member fronts in (F4.1 "Steering"). */
@Composable
internal fun StatePill(text: String) {
    val p = LocalChorusPalette.current
    Text(text, color = p.accent, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1,
        modifier = Modifier.clip(CircleShape).background(p.accentSoft).padding(horizontal = 10.dp, vertical = 4.dp))
}

/** A 40 dp rounded tile holding an icon, in a colour's tint or the quiet surface (F4.1 "Archived members"). */
@Composable
internal fun IconTile(icon: Int, color: String?, size: androidx.compose.ui.unit.Dp) {
    val p = LocalChorusPalette.current
    val tones = color?.takeIf { parseHex(it) != null }?.let { tonesOf(it) }
    Box(Modifier.size(size).clip(RoundedCornerShape(size / 4)).background(tones?.tint ?: p.surface2), contentAlignment = Alignment.Center) {
        Glyph(icon, tones?.name ?: p.ink2, size * .5f)
    }
}

/** A row with a tile, a title, a line under it, a count badge and a chevron (F4.1 Groups). */
@Composable
internal fun TileRow(tile: @Composable () -> Unit, title: String, subtitle: String?, count: Int?, onClick: () -> Unit) {
    val p = LocalChorusPalette.current
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 60.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        tile()
        Column(Modifier.weight(1f)) {
            Text(title, color = p.ink, fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, color = p.ink3, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        count?.let { Text("$it", color = p.ink, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, lineHeight = 14.sp,
            modifier = Modifier.clip(CircleShape).background(p.surface3).padding(horizontal = 7.dp, vertical = 2.dp)) }
        Glyph(R.drawable.ic_chevron_right, p.ink3, 18.dp)
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
private fun MemberRow(chorus: Chorus, model: Model, m: Member, layer: String?, selecting: Boolean, picked: Boolean, foldered: Boolean,
    onOpen: () -> Unit, onMove: (String?) -> Unit, onNewFolder: () -> Unit, onRoles: () -> Unit, onGroups: () -> Unit,
    onSelect: () -> Unit, onRestore: () -> Unit) {
    val p = LocalChorusPalette.current
    val settings = model.memberList
    val view = settings.view
    var menu by remember { mutableStateOf(false) }
    val banner = m.bannerBlob?.takeIf { view.banners }
    val shadow = if (banner != null) TextStyle(shadow = Shadow(p.bg, blurRadius = 8f)) else TextStyle.Default
    Box {
        Box(Modifier.fillMaxWidth().background(if (picked) p.accentSoft else Color.Transparent)
            .combinedClickable(onClick = onOpen, onLongClick = { if (!selecting) menu = true })) {
            if (banner != null) Box(Modifier.matchParentSize(), contentAlignment = Alignment.BottomEnd) { RowBanner(chorus, banner) }
            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (selecting) CheckMark(picked)
                Avatar(m.glyph, m.color, 44.dp, avatarBlob = m.avatarBlob, ring = false)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(m.shownName, color = p.ink, fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, style = shadow)
                    // inside a member who is a subsystem: "in Foxglove" rather than the group's name
                    val inside = Subsystems.parts(model, m.id).size.takeIf { Subsystems.groupOf(model, m.id) != null }
                    val within = model.membership.filter { m.id in it.value }.keys.firstNotNullOfOrNull { id ->
                        Subsystems.memberOf(model, id)?.takeIf { it.id != m.id }?.let { "in ${it.shownName}" } }
                    // "first only" reads as F4.1's grey "pronouns · role"; "all" keeps the coloured pills (D-100)
                    val role = settings.pills(m.id).takeIf { view.pills == "first" }?.firstOrNull()
                    val sub = listOfNotNull(m.pronouns, role, inside?.let { "$it inside" }, within).joinToString(" · ")
                    if (sub.isNotEmpty()) Text(sub, color = p.ink3, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, style = shadow)
                    val pills = settings.pills(m.id).takeIf { view.pills == "all" }.orEmpty()
                    if (pills.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (r in pills) RolePill(r)
                    }
                    if (view.bio) MemberListPrefs.bioLine(m.description)?.let {
                        Text(it, color = p.ink2, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, style = shadow)
                    }
                }
                layer?.let { StatePill(it) }
                if (!selecting) Glyph(R.drawable.ic_chevron_right, p.ink3, 18.dp)
            }
        }
        MenuDrawer(menu, { menu = false }, m.shownName, m.pronouns, leading = { Avatar(m.glyph, m.color, 40.dp, avatarBlob = m.avatarBlob, ring = false) }) {
            if (m.archived) MenuRow("Restore", R.drawable.ic_history) { menu = false; onRestore() }
            else {
                MenuRow("Groups", R.drawable.ic_users) { menu = false; onGroups() }
                MenuRow("Roles", R.drawable.ic_members) { menu = false; onRoles() }
                if (foldered) MenuRow("Select several", R.drawable.ic_check) { menu = false; onSelect() }
            }
            if (foldered) {
                SectionLabel("Move to")
                for (f in settings.folders) MenuRow(f.name, R.drawable.ic_folder, selected = settings.folderOf[m.id] == f.id) { menu = false; onMove(f.id) }
                MenuRow("Not in a folder", selected = m.id !in settings.folderOf) { menu = false; onMove(null) }
                MenuRow("New folder", R.drawable.ic_plus) { menu = false; onNewFolder() }
            }
        }
    }
}

/** A round check for picking rows (selection, group members). */
@Composable
internal fun CheckMark(on: Boolean, enabled: Boolean = true) {
    val p = LocalChorusPalette.current
    Box(Modifier.size(22.dp).clip(CircleShape).background(if (on) p.accent else Color.Transparent)
        .then(if (on) Modifier else Modifier.border(2.dp, if (enabled) p.ink3 else p.line, CircleShape)), contentAlignment = Alignment.Center) {
        if (on) Glyph(R.drawable.ic_check, p.onAccent, 14.dp)
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
        MenuRow("New folder", R.drawable.ic_plus, onClick = onNewFolder)
    }
}
