package garden.vayne.chorus.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
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
        Text("Welcome to Chorus", fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = p.ink, modifier = Modifier.padding(top = 32.dp))
        AnimatedContent(invite, label = "onboarding step") { step ->
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                when (step) {
                    null -> {
                        Text("A cozy place for plural systems and the people close to them. Paste the invite link you were given.", color = p.ink2)
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
                        Text("This link adds this phone to an account you already have.", color = p.ink2)
                        OnboardingCard {
                            error?.let { Text(it, color = p.danger) }
                            Button(enabled = !busy, onClick = ::join) { Text(if (busy) "Linking…" else "Link this phone") }
                            TextButton(enabled = !busy, onClick = { invite = null; error = null }) { Text("Use another link") }
                        }
                    }
                    else -> {
                        if (step.isNotEmpty()) {
                            Text("Who's joining?", color = p.ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                KindChoice("We're a system", "More than one of us shares this body. Chorus keeps track of " +
                                    "members, who's fronting and front history, and lets each of us post and chat as ourselves.",
                                    kind == "system") { kind = "system" }
                                KindChoice("Just me", "I'm one person (a singlet). Follow friends, including plural ones, see " +
                                    "who's fronting when they share it, and chat and post, without the switching tools.",
                                    kind == "person") { kind = "person" }
                            }
                        } else Text("Give your system (or yourself) a name to start.", color = p.ink2)
                        OnboardingCard {
                            ChorusField(
                                value = name, onValueChange = { name = it },
                                label = { Text(when { step.isEmpty() -> "Name (your system, or you)"; kind == "system" -> "Your system's name"; else -> "Your name" }) },
                                placeholder = { Text(if (kind == "system") "The Stars" else "Alex") },
                                singleLine = true, modifier = Modifier.fillMaxWidth(),
                            )
                            ChorusField(
                                value = handle, onValueChange = { v -> handle = v.removePrefix("@").lowercase().filter { it in 'a'..'z' || it in '0'..'9' || it == '_' }.take(32) },
                                label = { Text("Handle (optional)") }, prefix = { Text("@") },
                                supportingText = { Text("Friends follow you with it.") },
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

@Composable
private fun KindChoice(title: String, body: String, selected: Boolean, onSelect: () -> Unit) {
    val p = LocalChorusPalette.current
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier.fillMaxWidth().background(if (selected) p.accentSoft else p.surface, shape)
            .border(if (selected) 2.dp else 1.dp, if (selected) p.accent else p.line, shape)
            .selectable(selected, role = Role.RadioButton, onClick = onSelect)
            .heightIn(min = 56.dp).padding(start = 4.dp, top = 12.dp, end = 16.dp, bottom = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = p.accent),
            modifier = Modifier.padding(12.dp))
        Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = p.ink, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text(body, color = p.ink2, fontSize = 14.sp, lineHeight = 20.sp)
        }
    }
}
