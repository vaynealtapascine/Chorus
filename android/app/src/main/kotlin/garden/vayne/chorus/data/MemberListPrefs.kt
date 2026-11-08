package garden.vayne.chorus.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

// The Members list's own settings (D-100): role pills, a line of bio, banner backgrounds, folders
// and a flat view. All account prefs, so they reach the system's devices with no server change;
// mirrors web member-list.ts (shared fixture ui_member_list.json). Display only.

data class MemberFolder(val id: String, val name: String) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name)
}

/** [pills] is `off`, `first` or `all`; [roleField] absent picks one named "Role(s)". With nothing
 *  saved the list starts as Figma F4.1 draws it: the first role, no bio, no banners, flat (D-139). */
data class MembersView(val pills: String = "first", val roleField: String? = null, val bio: Boolean = false,
    val banners: Boolean = false, val flat: Boolean = true) {
    fun toJson(): JSONObject = JSONObject().put("pills", pills).put("bio", bio).put("banners", banners).put("flat", flat)
        .apply { roleField?.let { put("role_field", it) } }
}

/** A custom field that can feed role pills (`multi_select`, `select` or `text`). */
data class RoleFieldDef(val id: String, val name: String, val type: String, val choices: List<String>, val options: JSONObject) {
    val several: Boolean get() = type != "select"
}

data class MemberListSettings(val view: MembersView = MembersView(), val folders: List<MemberFolder> = emptyList(),
    val folderOf: Map<String, String> = emptyMap(), val roleFields: List<RoleFieldDef> = emptyList(),
    /** member id → field id → raw value */
    val values: Map<String, Map<String, Any?>> = emptyMap()) {
    /** The chosen roles field, else one named "Role" or "Roles", else the first several-choice field. */
    val roleField: RoleFieldDef? get() = view.roleField?.let { id -> roleFields.find { it.id == id } }
        ?: roleFields.find { MemberListPrefs.ROLE_NAME.matches(it.name.trim()) } ?: roleFields.find { it.type == "multi_select" }

    /** Whether a dedicated roles field exists (else offer to set one up). */
    val hasRolesField: Boolean get() = roleFields.any { it.type == "multi_select" || MemberListPrefs.ROLE_NAME.matches(it.name.trim()) }

    fun roles(memberId: String, field: RoleFieldDef? = roleField): List<String> =
        field?.let { MemberListPrefs.rolesOf(values[memberId]?.get(it.id)) }.orEmpty()

    fun pills(memberId: String): List<String> = MemberListPrefs.pillsFor(roles(memberId), view.pills)
}

data class MemberSection(val folder: MemberFolder?, val members: List<Member>)

object MemberListPrefs {
    const val VIEW = "members.view"
    const val FOLDERS = "member_folders"
    const val FOLDER_OF = "member_folder:"
    internal val ROLE_NAME = Regex("^roles?$", RegexOption.IGNORE_CASE)
    private val ROLE_TYPES = setOf("multi_select", "select", "text")

    fun parse(p: JSONObject, accountId: String): MemberListSettings {
        val rows = p.optJSONObject("rows")
        val prefs = rows?.optJSONObject("pref")
        fun value(key: String): Any? {
            val pending = prefs?.optJSONObject("||$key")?.takeIf { it.optBoolean("exists") }
            val stamped = prefs?.optJSONObject("$accountId||$key")?.takeIf { it.optBoolean("exists") }
            return (pending ?: stamped)?.optJSONObject("fields")?.opt("value")
        }
        val v = value(VIEW) as? JSONObject
        // a saved view's missing keys keep D-100's first defaults, so a saved view never changes (D-139)
        val base = MembersView(pills = "all", bio = true, banners = true, flat = false)
        val view = if (v == null) MembersView() else MembersView(
            pills = v.opt("pills").takeIf { it == "off" || it == "first" || it == "all" } as? String ?: base.pills,
            roleField = (v.opt("role_field") as? String)?.takeIf { it.isNotEmpty() },
            bio = v.opt("bio") as? Boolean ?: base.bio,
            banners = v.opt("banners") as? Boolean ?: base.banners,
            flat = v.opt("flat") as? Boolean ?: base.flat)
        val raw = value(FOLDERS) as? JSONArray
        val folders = mutableListOf<MemberFolder>()
        for (i in 0 until (raw?.length() ?: 0)) {
            val f = raw?.opt(i) as? JSONObject ?: continue
            val id = f.opt("id") as? String ?: continue
            if (id.isEmpty() || folders.any { it.id == id }) continue
            folders += MemberFolder(id, (f.opt("name") as? String)?.takeIf { it.isNotBlank() } ?: "Folder")
        }
        val ids = folders.map { it.id }.toSet()
        // a member filed in a folder that was since deleted is simply not in a folder
        val folderOf = keyed(prefs, accountId, FOLDER_OF).mapNotNull { (m, f) -> (f as? String)?.takeIf { it in ids }?.let { m to it } }.toMap()
        return MemberListSettings(view, folders, folderOf, roleFields(rows), values(rows))
    }

    private fun roleFields(rows: JSONObject?): List<RoleFieldDef> {
        val defs = rows?.optJSONObject("field_def") ?: return emptyList()
        return defs.keys().asSequence().mapNotNull { id ->
            val f = defs.optJSONObject(id)?.takeIf { it.optBoolean("exists") }?.optJSONObject("fields") ?: return@mapNotNull null
            val type = f.optString("type", "text")
            if (!f.isNull("deleted_at") || type !in ROLE_TYPES) return@mapNotNull null
            val options = f.optJSONObject("options") ?: JSONObject()
            val choices = options.optJSONArray("choices")?.let { a -> (0 until a.length()).mapNotNull { a.opt(it) as? String } }.orEmpty()
            Triple(f.optString("sort_key").ifEmpty { f.optString("name") }, id, RoleFieldDef(id, f.optString("name", "Field"), type, choices, options))
        }.sortedWith(compareBy({ it.first }, { it.second })).map { it.third }.toList()
    }

    private fun values(rows: JSONObject?): Map<String, Map<String, Any?>> {
        val v = rows?.optJSONObject("field_value") ?: return emptyMap()
        val out = HashMap<String, HashMap<String, Any?>>()
        for (key in v.keys()) {
            val bar = key.lastIndexOf('|')
            if (bar <= 0) continue
            val f = v.optJSONObject(key)?.takeIf { it.optBoolean("exists") }?.optJSONObject("fields") ?: continue
            out.getOrPut(key.substring(0, bar)) { HashMap() }[key.substring(bar + 1)] = f.opt("value")
        }
        return out
    }

    /** Account-wide (no device) prefs under [prefix]; a pending local change wins, null clears. */
    private fun keyed(prefs: JSONObject?, accountId: String, prefix: String): Map<String, Any?> {
        val pending = HashMap<String, Any?>()
        val stamped = HashMap<String, Any?>()
        for (k in prefs?.keys()?.asSequence().orEmpty()) {
            val parts = k.split('|', limit = 3)
            if (parts.size != 3 || parts[1].isNotEmpty() || (parts[0].isNotEmpty() && parts[0] != accountId)) continue
            val id = parts[2].takeIf { it.startsWith(prefix) }?.removePrefix(prefix)?.takeIf { it.isNotBlank() } ?: continue
            val row = prefs?.optJSONObject(k)?.takeIf { it.optBoolean("exists") } ?: continue
            (if (parts[0].isEmpty()) pending else stamped)[id] = row.optJSONObject("fields")?.opt("value")?.takeIf { it != JSONObject.NULL }
        }
        return stamped + pending
    }

    /** A member's roles from a field value: a choice list, one choice, or comma-separated text. */
    fun rolesOf(value: Any?): List<String> {
        val parts: List<Any?> = when (value) {
            is JSONArray -> List(value.length()) { value.opt(it) }
            is String -> value.split(',', ';')
            else -> emptyList()
        }
        val out = mutableListOf<String>()
        for (p in parts) { val t = (p as? String)?.trim() ?: continue; if (t.isNotEmpty() && t !in out) out += t }
        return out
    }

    fun pillsFor(roles: List<String>, mode: String): List<String> = when (mode) { "off" -> emptyList(); "first" -> roles.take(1); else -> roles }

    /** The first line of a bio with its spacing folded, or nothing. */
    fun bioLine(text: String?): String? = text?.split('\n')?.map { it.replace(Regex("\\s+"), " ").trim() }?.firstOrNull { it.isNotEmpty() }

    private val PILLS = listOf("#B99CE8", "#F08C7F", "#EEAA62", "#DCA3CC", "#8FB8E8", "#86BFA5", "#C9B37A")
    /** A steady pill colour for a role name whatever its case (same hash as web). */
    fun roleColor(role: String): String {
        var h = 0L
        for (c in role.lowercase(Locale.ROOT)) h = (h * 31 + c.code) and 0xFFFFFFFFL
        return PILLS[(h % PILLS.size).toInt()]
    }

    /** Folders in their arranged order (empty ones too), then everyone not in one; each keeps the members' order. */
    fun sections(members: List<Member>, folders: List<MemberFolder>, folderOf: Map<String, String>): List<MemberSection> {
        val ordered = MemberOrdering.ordered(members)
        val known = folders.map { it.id }.toSet()
        return folders.map { f -> MemberSection(f, ordered.filter { folderOf[it.id] == f.id }) } +
            MemberSection(null, ordered.filter { folderOf[it.id] !in known })
    }

    fun addFolder(folders: List<MemberFolder>, id: String, name: String) = folders + MemberFolder(id, name.trim().ifEmpty { "Folder" })
    fun renameFolder(folders: List<MemberFolder>, id: String, name: String) = folders.map { if (it.id == id) it.copy(name = name.trim().ifEmpty { it.name }) else it }
    fun removeFolder(folders: List<MemberFolder>, id: String) = folders.filter { it.id != id }
    /** Moves a folder [by] places (−1 earlier, +1 later), staying in bounds. */
    fun moveFolder(folders: List<MemberFolder>, id: String, by: Int): List<MemberFolder> {
        val i = folders.indexOfFirst { it.id == id }
        if (i < 0) return folders
        val j = (i + by).coerceIn(0, folders.size - 1)
        if (i == j) return folders
        return folders.toMutableList().apply { add(j, removeAt(i)) }
    }

    fun viewPayload(view: MembersView): JSONObject = AccountPrefs.payload(VIEW, view.toJson())
    fun foldersPayload(folders: List<MemberFolder>): JSONObject = AccountPrefs.payload(FOLDERS, JSONArray(folders.map { it.toJson() }))
    fun filePayload(memberId: String, folderId: String?): JSONObject = AccountPrefs.payload(FOLDER_OF + memberId, folderId ?: JSONObject.NULL)

    /** A roles field's new value after tapping [choice]: one choice toggles, several add or drop it,
     *  and a text field keeps them comma-separated. */
    fun toggled(field: RoleFieldDef, chosen: List<String>, choice: String): Any {
        if (field.type == "select") return if (chosen.firstOrNull() == choice) "" else choice
        val next = if (choice in chosen) chosen - choice else chosen + choice
        return if (field.type == "text") next.joinToString(", ") else JSONArray(next)
    }
}
