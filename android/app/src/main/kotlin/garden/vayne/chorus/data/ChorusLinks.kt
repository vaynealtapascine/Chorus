package garden.vayne.chorus.data

import org.json.JSONArray
import java.net.URI

/** Links to a post, an entry or a message (D-109): `<server>/post/<id>` and `<server>/message/<id>`,
 *  like web's `share-links.ts`. They open in the app and, in a chat, show as an embed; only links to
 *  this server count, and a reader sees an embed only for what they may already see. */
object ChorusLinks {
    data class Target(val kind: String, val id: String)

    private val ID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    /** The link to share for a post (`post`) or a message (`message`). */
    fun linkTo(base: String, kind: String, id: String): String = "${base.trimEnd('/')}/$kind/$id"

    private fun origin(u: URI): String? {
        val scheme = u.scheme?.lowercase() ?: return null
        val host = u.host?.lowercase() ?: return null
        val port = if (u.port != -1) u.port else if (scheme == "https") 443 else if (scheme == "http") 80 else -1
        return "$scheme://$host:$port"
    }

    /** What a link points at, if it is one of ours on this server: the share forms, and the web app's
     *  own addresses (`#/journal/post/<id>`, `#/chat/<channel>/<id>`) copied from a browser. */
    fun parse(href: String, base: String): Target? {
        val u = runCatching { URI(href.trim()) }.getOrNull() ?: return null
        val b = runCatching { URI(base.trim()) }.getOrNull() ?: return null
        if (origin(u) == null || origin(u) != origin(b)) return null
        val path = u.rawPath.orEmpty().split('/').filter { it.isNotEmpty() }
        if (path.size == 2 && (path[0] == "post" || path[0] == "message") && ID.matches(path[1])) return Target(path[0], path[1].lowercase())
        val hash = u.rawFragment.orEmpty().removePrefix("/").split('/').filter { it.isNotEmpty() }
        if (hash.size == 3 && hash[0] == "journal" && hash[1] == "post" && ID.matches(hash[2])) return Target("post", hash[2].lowercase())
        if (hash.size == 3 && hash[0] == "chat" && ID.matches(hash[2])) return Target("message", hash[2].lowercase())
        return null
    }

    /** The links in a message, in order and once each: `url` entities (their text) and `text_link`
     *  entities (their target). Offsets are UTF-16, as Kotlin strings are. */
    fun linksIn(text: String, entities: String, base: String): List<Target> {
        val list = runCatching { JSONArray(entities) }.getOrNull() ?: return emptyList()
        val out = LinkedHashSet<Target>()
        for (i in 0 until list.length()) {
            val e = list.optJSONObject(i) ?: continue
            val start = e.optInt("offset", -1)
            val end = start + e.optInt("length", 0)
            val href = when (e.optString("type")) {
                "url" -> if (start >= 0 && end <= text.length && end > start) text.substring(start, end) else null
                "text_link" -> e.optString("url").takeIf { it.isNotBlank() }
                else -> null
            }
            href?.let { parse(it, base) }?.let { out.add(it) }
        }
        return out.toList()
    }
}
