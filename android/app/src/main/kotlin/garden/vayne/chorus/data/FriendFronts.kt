package garden.vayne.chorus.data

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Who's fronting in the accounts this one follows, as far as each shares it: the server's
 * privacy-filtered follower view (NOTIFICATIONS.md §5), kept for chat headers and Friends so a
 * singlet (or another system) can see who they're talking to. Keyed by viewer and followed
 * account, so a second account on this device never sees the first one's views.
 */
object FriendFronts {
    private val _views = MutableStateFlow<Map<String, FollowerView>>(emptyMap())
    val views: StateFlow<Map<String, FollowerView>> = _views
    private val fetched = ConcurrentHashMap<String, Long>()

    fun key(viewer: String, account: String) = "$viewer|$account"

    fun put(viewer: String, account: String, view: FollowerView?, now: Long = System.currentTimeMillis()) {
        val k = key(viewer, account)
        fetched[k] = now
        _views.update { if (view == null) it - k else it + (k to view) }
    }

    /** Ask the server again when the last answer is older than [maxAgeMs]; not following is an empty answer. */
    suspend fun refresh(dev: DeviceRecord, account: String, maxAgeMs: Long = 60_000, now: Long = System.currentTimeMillis()) {
        val last = fetched[key(dev.accountId, account)]
        if (last != null && now - last < maxAgeMs) return
        put(dev.accountId, account, runCatching { PeopleApi.followerView(PeopleApi.view(dev, account)) }.getOrNull(), now)
    }

    /** "Kai is fronting", "Kai & Wren are fronting", "Kai, Wren & 2 more are fronting"; null when nobody is shown. */
    fun line(names: List<String>): String? = when (names.size) {
        0 -> null
        1 -> "${names[0]} is fronting"
        2 -> "${names[0]} & ${names[1]} are fronting"
        else -> "${names[0]}, ${names[1]} & ${names.size - 2} more are fronting"
    }
}
