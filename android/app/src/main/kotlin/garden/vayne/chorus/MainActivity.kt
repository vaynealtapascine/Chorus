package garden.vayne.chorus

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import garden.vayne.chorus.data.Chorus
import garden.vayne.chorus.data.Status
import garden.vayne.chorus.data.SyncWork
import garden.vayne.chorus.designsystem.ChorusTheme
import garden.vayne.chorus.designsystem.LocalChorusPalette
import garden.vayne.chorus.ui.History
import garden.vayne.chorus.ui.Home
import garden.vayne.chorus.ui.Members
import garden.vayne.chorus.ui.Onboarding

class MainActivity : ComponentActivity() {
    private var inviteLink = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        inviteLink.value = inviteFrom(intent)
        val chorus = Chorus.get(this)
        SyncWork.schedulePeriodic(this)
        // switch notifications (M8.1): ask once on Android 13+, then register with ntfy if present
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        garden.vayne.chorus.data.Push.ensure(this)
        setContent { ChorusTheme { App(chorus, inviteLink.value) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        inviteFrom(intent)?.let { inviteLink.value = it }
    }

    override fun onStart() {
        super.onStart()
        Chorus.get(this).setForeground(true)
    }

    override fun onStop() {
        Chorus.get(this).setForeground(false)
        super.onStop()
    }

    private fun inviteFrom(i: Intent?): String? =
        i?.data?.toString()?.takeIf { "/i/" in it } ?: i?.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { "/i/" in it }
}

private enum class Tab(val label: String) { Home("Home"), Members("Members"), History("History") }

@Composable
private fun App(chorus: Chorus, invite: String?) {
    val p = LocalChorusPalette.current
    val status by chorus.status.collectAsState()
    val model by chorus.model.collectAsState()
    var tab by rememberSaveable { mutableStateOf(Tab.Home) }

    when (status) {
        Status.Loading -> Box(Modifier.fillMaxSize().background(p.bg))
        Status.NoDevice -> Onboarding(chorus, invite)
        Status.StorageError -> Box(Modifier.fillMaxSize().background(p.bg).padding(24.dp), contentAlignment = Alignment.Center) {
            Text("The local replica could not be opened or saved. Keep this app's data intact and check the device storage.", color = p.danger)
        }
        else -> Column(Modifier.fillMaxSize().background(p.bg)) {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Chorus", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = p.ink)
                Spacer(Modifier.weight(1f))
                val (dot, label) = when (status) {
                    Status.Live -> p.ok to "live"
                    Status.Connecting -> p.warn to "connecting"
                    else -> p.ink3 to "offline"
                }
                Box(Modifier.size(8.dp).background(dot, CircleShape))
                Spacer(Modifier.width(6.dp))
                Text(label, fontSize = 12.sp, color = p.ink3)
            }
            Box(Modifier.weight(1f)) {
                when (tab) {
                    Tab.Home -> Home(chorus, model)
                    Tab.Members -> Members(chorus, model)
                    Tab.History -> History(model)
                }
            }
            Row(
                Modifier.fillMaxWidth().background(p.surface).navigationBarsPadding().padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                Tab.entries.forEach { t ->
                    Text(
                        t.label,
                        color = if (t == tab) p.accent else p.ink2,
                        fontWeight = if (t == tab) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.clickable { tab = t }.padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}
