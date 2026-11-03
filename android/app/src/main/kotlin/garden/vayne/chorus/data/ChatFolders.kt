package garden.vayne.chorus.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Chat folders (D-113, Figma F5.25; web's chat-folders.ts): the built-in folders (All, Internal,
 * Shared, Direct) can be hidden, every folder can be put in order, and folders of our own hold
 * chosen chats, whole kinds of chat (less any left out), or both, optionally only while unread or
 * leaving muted chats out. With the chat list's display choices it is one account pref,
 * `chat_folders`, so the system's devices share it; which folder is open stays on the device.
 */
object ChatFolders {
    const val PREF = "chat_folders"
    val BUILT_IN = listOf("all", "internal", "shared", "direct")
    /** The kinds of chat, as the built-in folders sort them ([ChatPresentation.folder]). */
    val KINDS = listOf("internal", "shared", "direct")

    data class Custom(val id: String, val name: String, val chats: List<String> = emptyList(),
        val kinds: Set<String> = emptySet(), val except: Set<String> = emptySet(),
        val unreadOnly: Boolean = false, val hideMuted: Boolean = false, val preset: String? = null)

    data class Settings(val order: List<String> = BUILT_IN, val hidden: Set<String> = emptySet(),
        val custom: List<Custom> = emptyList(), val showHere: Boolean = true, val compact: Boolean = false) {
        /** Every folder in display order: the saved order first, then any it doesn't name yet. */
        val ordered: List<String> get() {
            val all = BUILT_IN + custom.map { it.id }
            return order.filter { it in all }.distinct() + all.filter { it !in order }
        }
        /** The folders shown as tabs. */
        val visible: List<String> get() = ordered.filter { it !in hidden }
        fun custom(id: String): Custom? = custom.find { it.id == id }
    }

    /** "internal", "shared" or "direct", as the built-in folders sort a chat. */
    fun kindOf(channel: ChatChannel, space: ChatSpace): String = ChatPresentation.folder(channel, space).name.lowercase()

    fun label(settings: Settings, id: String): String = when (id) {
        "all" -> "All"
        "internal" -> "Internal"
        "shared" -> "Shared"
        "direct" -> "Direct"
        else -> settings.custom(id)?.name?.ifBlank { "Folder" } ?: "Folder"
    }

    /** Whether a chat of [kind] is in folder [id] right now ([unread] for whoever is reading). */
    fun contains(settings: Settings, id: String, channelId: String, kind: String, unread: Int, muted: Boolean): Boolean = when (id) {
        "all" -> true
        in KINDS -> kind == id
        else -> settings.custom(id)?.let { f -> holds(f, channelId, kind) && (!f.unreadOnly || unread > 0) && (!f.hideMuted || !muted) } ?: false
    }

    /** Chosen for [f], by name or by kind, before its unread/muted filters. */
    fun holds(f: Custom, channelId: String, kind: String): Boolean =
        channelId in f.chats || (kind in f.kinds && channelId !in f.except)

    /** Put a chat in or out of [f]: a chosen chat, or a chat of an included kind left out. */
    fun setChat(f: Custom, channelId: String, kind: String, inside: Boolean): Custom = if (inside) f.copy(
        chats = if (kind in f.kinds || channelId in f.chats) f.chats.filterNot { it == channelId && kind in f.kinds } else f.chats + channelId,
        except = f.except - channelId)
    else f.copy(chats = f.chats - channelId, except = if (kind in f.kinds) f.except + channelId else f.except - channelId)

    /** Include or stop including every chat of [kind]; its left-out chats are forgotten either way. */
    fun setKind(f: Custom, kind: String, on: Boolean, kindOf: (String) -> String?): Custom = f.copy(
        kinds = if (on) f.kinds + kind else f.kinds - kind,
        except = f.except.filterNot { kindOf(it) == kind }.toSet(),
        chats = if (on) f.chats.filterNot { kindOf(it) == kind } else f.chats)

    /** Move folder [id] to place [to] in [Settings.ordered]. */
    fun move(settings: Settings, id: String, to: Int): Settings {
        val order = settings.ordered.toMutableList()
        val from = order.indexOf(id)
        if (from < 0 || to !in order.indices || from == to) return settings
        order.add(to, order.removeAt(from))
        return settings.copy(order = order)
    }

    fun withCustom(settings: Settings, folder: Custom): Settings = settings.copy(
        custom = if (settings.custom.any { it.id == folder.id }) settings.custom.map { if (it.id == folder.id) folder else it }
            else settings.custom + folder)

    fun without(settings: Settings, id: String): Settings =
        settings.copy(custom = settings.custom.filterNot { it.id == id }, order = settings.order - id, hidden = settings.hidden - id)

    /** Figma F5.25's suggestions, each a folder of our own to start from. */
    data class Suggestion(val preset: String, val title: String, val subtitle: String)
    val SUGGESTIONS = listOf(
        Suggestion("unread", "Unread", "Unread for whoever is reading"),
        Suggestion("handover", "Handover and notes", "Your handover and notes chats"),
        Suggestion("unmuted", "Not muted", "Every chat but the muted ones"),
    )

    /** A new folder from a suggestion; "Handover and notes" starts with our own chats named that way. */
    fun fromSuggestion(id: String, s: Suggestion, ours: List<Pair<String, String>>): Custom = when (s.preset) {
        "unread" -> Custom(id, s.title, kinds = KINDS.toSet(), unreadOnly = true, preset = s.preset)
        "unmuted" -> Custom(id, s.title, kinds = KINDS.toSet(), hideMuted = true, preset = s.preset)
        else -> Custom(id, s.title, chats = ours.filter { (_, name) -> Regex("handover|notes?\\b", RegexOption.IGNORE_CASE).containsMatchIn(name) }
            .map { it.first }, preset = s.preset)
    }

    fun parse(value: Any?): Settings {
        val o = value as? JSONObject ?: return Settings()
        fun strings(a: JSONArray?) = (0 until (a?.length() ?: 0)).mapNotNull { a!!.opt(it) as? String }.filter { it.isNotBlank() }
        val custom = (0 until (o.optJSONArray("custom")?.length() ?: 0)).mapNotNull { n ->
            val f = o.optJSONArray("custom")!!.optJSONObject(n) ?: return@mapNotNull null
            val id = f.optString("id").takeIf { it.isNotBlank() && it !in BUILT_IN } ?: return@mapNotNull null
            Custom(id, f.optString("name"), strings(f.optJSONArray("chats")).distinct(),
                strings(f.optJSONArray("kinds")).filter { it in KINDS }.toSet(), strings(f.optJSONArray("except")).toSet(),
                f.optBoolean("unread_only"), f.optBoolean("hide_muted"), f.optString("preset").ifBlank { null })
        }.distinctBy { it.id }
        return Settings(strings(o.optJSONArray("order")).ifEmpty { BUILT_IN }, strings(o.optJSONArray("hidden")).toSet(), custom,
            o.optBoolean("show_here", true), o.optBoolean("compact", false))
    }

    fun toJson(s: Settings): JSONObject = JSONObject()
        .put("order", JSONArray(s.ordered))
        .put("hidden", JSONArray(s.hidden.filter { it in s.ordered }.sorted()))
        .put("custom", JSONArray(s.custom.map { f ->
            JSONObject().put("id", f.id).put("name", f.name).put("chats", JSONArray(f.chats))
                .put("kinds", JSONArray(KINDS.filter { it in f.kinds })).put("except", JSONArray(f.except.sorted()))
                .put("unread_only", f.unreadOnly).put("hide_muted", f.hideMuted).apply { f.preset?.let { put("preset", it) } }
        }))
        .put("show_here", s.showHere).put("compact", s.compact)

    fun payload(s: Settings): JSONObject = AccountPrefs.payload(PREF, toJson(s))
}
