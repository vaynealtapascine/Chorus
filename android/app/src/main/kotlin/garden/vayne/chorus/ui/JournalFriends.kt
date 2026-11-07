package garden.vayne.chorus.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import garden.vayne.chorus.data.*
import garden.vayne.chorus.designsystem.LocalChorusPalette

/** The source id under which replies to our posts page (D-127); never an account id. */
private const val REPLIES = "replies-to-us"

@Composable
internal fun JournalFriends(chorus: Chorus, model: Model, onThread: (String) -> Unit, onReply: (String) -> Unit,
    onProfile: ((String, String) -> Unit)? = null, onQuote: ((String) -> Unit)? = null) {
    val p = LocalChorusPalette.current
    // each followed account's pages so far, plus other accounts' replies to our posts (D-127), which
    // come from anyone whose reply we may read; older ones load near the bottom (owner, 2026-11-07)
    var sources by remember { mutableStateOf(listOf<FriendsFeed.Source>()) }
    val posts = remember(sources) { FriendsFeed.shown(sources) }
    var loading by remember { mutableStateOf(true) }
    var loadingOlder by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) }
    LaunchedEffect(chorus.device?.session, refresh) {
        sources = emptyList(); loading = true; error = null
        try {
            val dev = checkNotNull(chorus.device)
            val following = PeopleApi.list(dev).following.filter { it.status == "active" }
            val loaded = mutableListOf<FriendsFeed.Source>()
            for (follow in following) {
                try {
                    val page = PeopleApi.posts(dev, follow.account.id)
                    loaded += FriendsFeed.Source(follow.account.id, follow.account.shownName, page, page.size >= FriendsFeed.PAGE)
                }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { error = "Some friends’ posts could not be loaded. Try again when connected." }
            }
            try {
                val activity = JournalActivityApi.refresh(dev)
                loaded += FriendsFeed.Source(REPLIES, "", activity.replies, activity.items.size >= JournalActivityApi.PAGE)
            }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { error = "Replies to your posts could not be loaded. Try again when connected." }
            sources = loaded
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { error = "Friends’ posts need a connection. Your own journal is available in ${if (model.isPerson) "My posts" else "Our system"}." }
        finally { loading = false }
    }
    val list = rememberLazyListState()
    val nearEnd by remember { derivedStateOf { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= list.layoutInfo.totalItemsCount - 4 } == true } }
    LaunchedEffect(nearEnd, sources, loading) {
        if (!nearEnd || loading || loadingOlder) return@LaunchedEffect
        val waiting = FriendsFeed.toLoad(sources).takeIf { it.isNotEmpty() } ?: return@LaunchedEffect
        val dev = chorus.device ?: return@LaunchedEffect
        loadingOlder = true
        try {
            val next = sources.toMutableList()
            for (i in waiting) {
                val s = next[i]
                next[i] = if (s.accountId == REPLIES) {
                    val page = JournalActivityApi.load(dev, before = s.oldest)
                    s.copy(posts = s.posts + page.replies, more = page.items.size >= JournalActivityApi.PAGE && page.replies.isNotEmpty())
                } else {
                    val page = PeopleApi.posts(dev, s.accountId, before = s.oldest)
                    s.copy(posts = s.posts + page, more = page.size >= FriendsFeed.PAGE)
                }
            }
            sources = next
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { error = "Older posts could not be loaded. Try again when connected." }
        finally { loadingOlder = false }
    }
    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = 80.dp)) {
        if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (error != null) item { Column(Modifier.padding(20.dp)) {
            Text(error.orEmpty(), color = p.ink2)
            TextButton(onClick = { refresh++ }) { Text("Try again") }
        } }
        if (!loading && error == null && posts.isEmpty()) item { Text("No shared posts yet. Posts from accounts you follow, and replies to yours, will appear here.",
            color = p.ink3, modifier = Modifier.padding(24.dp)) }
        items(posts, key = { it.second.id }) { (name, post) ->
            SharedPostPreview(post, name.ifEmpty { "Someone" }, chorus, model, onThread = { onThread(it.id) }, onReply = { onReply(it.id) },
                onProfile = onProfile, onQuote = onQuote?.let { quote -> { quote(it.id) } }, onOpenPost = onThread)
            HorizontalDivider(color = p.line)
        }
        if (loadingOlder) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp)) }
    }
}
