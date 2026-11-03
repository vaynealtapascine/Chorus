package garden.vayne.chorus.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import garden.vayne.chorus.data.*
import garden.vayne.chorus.designsystem.LocalChorusPalette

@Composable
internal fun JournalFriends(chorus: Chorus, model: Model, onThread: (String) -> Unit, onReply: (String) -> Unit,
    onProfile: ((String, String) -> Unit)? = null) {
    val p = LocalChorusPalette.current
    var posts by remember { mutableStateOf(listOf<Pair<String, SharedPost>>()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) }
    LaunchedEffect(chorus.device?.session, refresh) {
        posts = emptyList(); loading = true; error = null
        try {
            val dev = checkNotNull(chorus.device)
            val following = PeopleApi.list(dev).following.filter { it.status == "active" }
            val loaded = mutableListOf<Pair<String, SharedPost>>()
            for (follow in following) {
                try { loaded += PeopleApi.posts(dev, follow.account.id).map { follow.account.shownName to it } }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { error = "Some friends’ posts could not be loaded. Try again when connected." }
            }
            posts = loaded.sortedByDescending { it.second.occurredAt }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { error = "Friends’ posts need a connection. Your own journal is available in Our system." }
        finally { loading = false }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 80.dp)) {
        if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (error != null) item { Column(Modifier.padding(20.dp)) {
            Text(error.orEmpty(), color = p.ink2)
            TextButton(onClick = { refresh++ }) { Text("Try again") }
        } }
        if (!loading && error == null && posts.isEmpty()) item { Text("No shared posts yet. Posts from accounts you follow will appear here.",
            color = p.ink3, modifier = Modifier.padding(24.dp)) }
        items(posts, key = { it.second.id }) { (name, post) ->
            SharedPostPreview(post, name, chorus, model, onThread = { onThread(it.id) }, onReply = { onReply(it.id) }, onProfile = onProfile)
            HorizontalDivider(color = p.line)
        }
    }
}
