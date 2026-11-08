package garden.vayne.chorus.ui

import androidx.compose.animation.AnimatedContent
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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

/**
 * Join with an invite link (CLIENTS.md §2.4, FLOWS F1.1/F1.5): paste or open the link, then for a
 * new account say whether you're a system or just you (D-093), so a singlet never meets the
 * member, switching and front-history tools. A device link only needs the link.
 */
@Composable
fun Onboarding(chorus: Chorus, initialLink: String?) {
    val p = LocalChorusPalette.current
    val scope = rememberCoroutineScope()
    var link by remember { mutableStateOf(initialLink.orEmpty()) }
    // null: not looked at yet; "system"/"person": a new account; "device": another device; "": an older server
    var invite by remember { mutableStateOf<String?>(null) }
    var kind by remember { mutableStateOf("system") }
    var name by remember { mutableStateOf("") }
    var handle by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun look() {
        if (busy || Api.parseInvite(link) == null) return
        busy = true
        error = null
        scope.launch {
            try {
                val k = Api.inviteKind(link)
                if (k == "system" || k == "person") kind = k
                invite = k ?: ""
            } catch (e: Exception) {
                error = e.message ?: "Couldn't reach the server."
            } finally {
                busy = false
            }
        }
    }

    fun join() {
        busy = true
        error = null
        val account = invite != "device"
        scope.launch {
            try {
                chorus.adopt(Api.enrol(link, name.takeIf { account }, handle.takeIf { account },
                    kind.takeIf { account && invite != "" }))
            } catch (e: Exception) {
                error = e.message ?: "Couldn't reach the server."
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(initialLink) {
        if (!initialLink.isNullOrBlank()) {
            link = initialLink
            invite = null
            look()
        }
    }

    Column(
        Modifier.fillMaxSize().background(p.bg).safeDrawingPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // the wordmark, until the artwork arrives (UI21 Q-C)
        Text("Chorus", fontSize = 34.sp, fontWeight = FontWeight.Black, color = p.ink, modifier = Modifier.padding(top = 32.dp))
        AnimatedContent(invite, label = "onboarding step") { step ->
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                when (step) {
                    null -> {
                        OnboardingCard {
                            ChorusField(
                                value = link, onValueChange = { link = it; error = null },
                                label = { Text("Invite link") }, placeholder = { Text("https://…/i/…") },
                                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            error?.let { Text(it, color = p.danger) }
                            Button(enabled = !busy && Api.parseInvite(link) != null, onClick = ::look) {
                                Text(if (busy) "Checking…" else "Continue")
                            }
                        }
                    }
                    "device" -> {
                        OnboardingCard {
                            error?.let { Text(it, color = p.danger) }
                            Button(enabled = !busy, onClick = ::join) { Text(if (busy) "Linking…" else "Link this device") }
                            TextButton(enabled = !busy, onClick = { invite = null; error = null }) { Text("Use another link") }
                        }
                    }
                    else -> {
                        OnboardingCard {
                            // one toggle in place of D-093's two cards: the same account kind (UI21 F1.1)
                            if (step.isNotEmpty()) ToggleRow("I am plural", kind == "system", enabled = !busy) { kind = if (it) "system" else "person" }
                            ChorusField(
                                value = name, onValueChange = { name = it },
                                label = { Text(when { step.isEmpty() -> "Name (your system, or you)"; kind == "system" -> "Your system's name"; else -> "Your name" }) },
                                placeholder = { Text(if (kind == "system") "The Stars" else "Alex") },
                                singleLine = true, modifier = Modifier.fillMaxWidth(),
                            )
                            ChorusField(
                                value = handle, onValueChange = { v -> handle = v.removePrefix("@").lowercase().filter { it in 'a'..'z' || it in '0'..'9' || it == '_' }.take(32) },
                                label = { Text("Handle (optional)") }, prefix = { Text("@") },
                                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            error?.let { Text(it, color = p.danger) }
                            Button(enabled = !busy && (handle.isEmpty() || handle.length >= 2), onClick = ::join) {
                                Text(if (busy) "Setting up…" else "Join")
                            }
                            TextButton(enabled = !busy, onClick = { invite = null; error = null }) { Text("Use another link") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OnboardingCard(content: @Composable () -> Unit) {
    val p = LocalChorusPalette.current
    Column(
        Modifier.fillMaxWidth().background(p.surface, RoundedCornerShape(20.dp))
            .border(1.dp, p.line, RoundedCornerShape(20.dp)).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) { content() }
}
