package garden.vayne.chorus

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import garden.vayne.chorus.designsystem.ChorusTheme
import garden.vayne.chorus.designsystem.LocalChorusPalette
import org.json.JSONObject
import uniffi.chorus_ffi.adaptColor
import uniffi.chorus_ffi.coreVersion

/** M0.3 skeleton: proves the Rust core loads on device. Replaced by the real app in M4. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ChorusTheme { Home() } }
    }
}

private data class Sample(val id: String, val name: String, val color: String, val sigil: String)

private val sample = listOf(
    Sample("kai", "Kai", "#C0694E", "🌌"),
    Sample("june", "June", "#5E8C61", "🔖"),
    Sample("rin", "Rin", "#fff27a", "❤️‍🔥"),
    Sample("moss", "Moss", "#6C7BD6", "🌿"),
)

private fun hex(s: String) = Color(android.graphics.Color.parseColor(s))

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun Home() {
    val p = LocalChorusPalette.current
    val dark = isSystemInDarkTheme()
    var fronting by remember { mutableStateOf("kai") }
    Column(
        Modifier.fillMaxSize().background(p.bg).safeDrawingPadding().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text("Chorus", fontSize = 28.sp, fontWeight = FontWeight.SemiBold, color = p.ink)
            Spacer(Modifier.weight(1f))
            Text("core ${coreVersion()}", fontSize = 12.sp, color = p.ink3)
        }
        val who = sample.first { it.id == fronting }
        val c = JSONObject(adaptColor(who.color, dark, "subtle"))
        Row(
            Modifier.fillMaxWidth().background(p.surface, RoundedCornerShape(20.dp))
                .border(1.dp, p.line, RoundedCornerShape(20.dp)).padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(56.dp).border(2.dp, hex(c.getString("ring")), CircleShape).background(p.surface2, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Text(who.sigil, fontSize = 26.sp) }
            Spacer(Modifier.width(16.dp))
            Column {
                Text(who.name, fontSize = 22.sp, fontWeight = FontWeight.Medium, color = hex(c.getString("name")))
                Text("is here", fontSize = 13.sp, color = p.ink2)
            }
        }
        Text("Quick switch", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = p.ink2)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            sample.forEach { m ->
                val mc = JSONObject(adaptColor(m.color, dark, "subtle"))
                Row(
                    Modifier.background(p.surface, CircleShape).border(1.dp, p.line, CircleShape)
                        .clickable { fronting = m.id }.padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(32.dp).border(2.dp, hex(mc.getString("ring")), CircleShape).background(p.surface2, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { Text(m.sigil) }
                    Spacer(Modifier.width(8.dp))
                    Text(m.name, color = hex(mc.getString("name")))
                }
            }
        }
    }
}
