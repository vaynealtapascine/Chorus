package garden.vayne.chorus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import garden.vayne.chorus.data.Api
import garden.vayne.chorus.data.Chorus
import garden.vayne.chorus.designsystem.LocalChorusPalette
import kotlinx.coroutines.launch

/** Join with an invite link (CLIENTS.md §2.4): a new system, or another device of an existing one. */
@Composable
fun Onboarding(chorus: Chorus, initialLink: String?) {
    val p = LocalChorusPalette.current
    val scope = rememberCoroutineScope()
    var link by remember { mutableStateOf(initialLink.orEmpty()) }
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(initialLink) {
        if (!initialLink.isNullOrBlank()) link = initialLink
    }

    Column(
        Modifier.fillMaxSize().background(p.bg).safeDrawingPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Welcome to Chorus", fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = p.ink, modifier = Modifier.padding(top = 32.dp))
        Text("A cozy home for your system. Paste the invite link you were given.", color = p.ink2)
        Column(
            Modifier.fillMaxWidth().background(p.surface, RoundedCornerShape(20.dp))
                .border(1.dp, p.line, RoundedCornerShape(20.dp)).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            OutlinedTextField(
                value = link, onValueChange = { link = it; error = null },
                label = { Text("Invite link") }, placeholder = { Text("https://…/i/…") },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("Name (your system, or you)") }, placeholder = { Text("The Stars") },
                supportingText = { Text("Leave empty when linking another device of your system.") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            error?.let { Text(it, color = p.danger) }
            Button(
                enabled = !busy && Api.parseInvite(link) != null,
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            chorus.adopt(Api.enrol(link, name))
                        } catch (e: Exception) {
                            error = e.message ?: "Couldn't reach the server."
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text(if (busy) "Setting up…" else "Continue") }
        }
    }
}
