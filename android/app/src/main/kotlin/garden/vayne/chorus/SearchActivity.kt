package garden.vayne.chorus

import android.os.Bundle
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import garden.vayne.chorus.data.Chorus
import garden.vayne.chorus.data.Front
import garden.vayne.chorus.data.Mode
import garden.vayne.chorus.data.Subject
import garden.vayne.chorus.designsystem.ChorusTheme
import garden.vayne.chorus.designsystem.LocalChorusPalette
import garden.vayne.chorus.data.Status
import garden.vayne.chorus.ui.Switcher
import garden.vayne.chorus.widget.QuickSwitchWidget
import garden.vayne.chorus.widget.PinnedShortcuts
import kotlinx.coroutines.launch

/**
 * The search launcher (CLIENTS.md §3.4): opens over the home screen with the keyboard up; type a
 * few letters, tap (or press Enter for the top match) and it switches and closes.
 */
class SearchActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val chorus = Chorus.get(this)
        if (intent.action == "garden.vayne.chorus.SWITCH_OUT") {
            if (savedInstanceState != null) { finish(); return }
            lifecycleScope.launch {
                try {
                    val model = chorus.awaitModel()
                    if (chorus.device == null || model.isPerson) throw IllegalStateException("Switching is for systems.")
                    Front.switch(chorus, emptyList(), "Switched out")
                    QuickSwitchWidget.refreshAll(this@SearchActivity)
                    Toast.makeText(this@SearchActivity, "Switched out", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this@SearchActivity, e.message ?: "Couldn't switch out", Toast.LENGTH_LONG).show()
                } finally { finish() }
            }
            return
        }
        val pinnedId = intent.getStringExtra(PinnedShortcuts.EXTRA_MEMBER_ID)
        if (pinnedId != null) {
            val pinnedAccount = intent.getStringExtra(PinnedShortcuts.EXTRA_ACCOUNT_ID)
            lifecycleScope.launch {
                try {
                    val model = chorus.awaitModel()
                    val member = model.active.find { it.id == pinnedId &&
                        (it.createdByAccountId == null || it.createdByAccountId == pinnedAccount) }
                    if (chorus.device?.accountId != pinnedAccount || member == null || model.isPerson) {
                        Toast.makeText(this@SearchActivity, "This shortcut is no longer available.", Toast.LENGTH_SHORT).show()
                    } else {
                        val subject = Subject("member", member.id, member.shownName, member.color, member.glyph, member.avatarBlob)
                        val label = Front.tap(chorus, subject, Mode.Replace)
                        QuickSwitchWidget.refreshAll(this@SearchActivity)
                        Toast.makeText(this@SearchActivity, label, Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(this@SearchActivity, e.message ?: "Couldn't switch", Toast.LENGTH_LONG).show()
                } finally { finish() }
            }
            return
        }
        setContent { ChorusTheme { Launcher(chorus, ::finish) } }
    }
}

/**
 * The launcher is the Switch drawer (F3.2) over the home screen, its search open: faces with their
 * layer tags, Replace or Add, and Switch, on Body's layers. Enter places the top match, then switches.
 */
@Composable
private fun Launcher(chorus: Chorus, close: () -> Unit) {
    val p = LocalChorusPalette.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val model by chorus.model.collectAsState()
    val status by chorus.status.collectAsState()
    if (model.isPerson) {
        Box(Modifier.fillMaxSize().background(Color(0x66000000)).clickable(onClick = close).statusBarsPadding().padding(16.dp),
            contentAlignment = Alignment.TopCenter) {
            Text("Quick switching is for systems", color = p.ink,
                modifier = Modifier.fillMaxWidth().background(p.bg, RoundedCornerShape(24.dp)).padding(20.dp))
        }
        return
    }
    // the switch snapshots who is here when it opens: wait for the saved state first
    if (status == Status.Loading) return
    if (chorus.device == null) { LaunchedEffect(Unit) { close() }; return }
    Switcher(chorus, model, search = true) { QuickSwitchWidget.refreshAll(ctx); close() }
}
