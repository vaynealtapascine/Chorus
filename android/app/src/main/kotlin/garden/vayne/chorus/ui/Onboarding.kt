package garden.vayne.chorus.ui

import android.content.ClipboardManager
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import garden.vayne.chorus.data.Api
import garden.vayne.chorus.data.Chorus
import garden.vayne.chorus.data.DeviceLinks
import garden.vayne.chorus.data.Setup
import garden.vayne.chorus.data.Welcoming
import garden.vayne.chorus.designsystem.HankenGrotesk
import garden.vayne.chorus.designsystem.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Join Chorus on one screen (F1.1/F1.5, CLIENTS.md §2.4): the invite, a name, a handle and what
 * this device is called, with *I am plural* (D-093: on = a system, off = one person; the invite's
 * kind sets it). A link for another device of an account needs only the link: **Link device**
 * scans the QR on that account's Link a device screen and joins at once.
 */
@Composable
fun Onboarding(chorus: Chorus, initialLink: String?) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var link by remember { mutableStateOf(initialLink.orEmpty()) }
    // null: not looked at yet; "system"/"person": a new account; "device": another device; "": an older server
    var invite by remember { mutableStateOf<String?>(null) }
    var plural by remember { mutableStateOf(true) }
    var name by remember { mutableStateOf("") }
    var handle by remember { mutableStateOf("") }
    var device by remember { mutableStateOf(DeviceLinks.defaultName(Build.MANUFACTURER, Build.MODEL)) }
    var busy by remember { mutableStateOf(false) }
    var looking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var scanned by remember { mutableStateOf(false) }
    val valid = Api.parseInvite(link) != null
    val forDevice = invite == "device"

    fun join() {
        if (busy || !Welcoming.ready(link, invite, name, handle, device)) return
        busy = true; error = null
        val account = !forDevice
        scope.launch {
            try {
                val dev = Api.enrol(link, name.takeIf { account }, handle.takeIf { account },
                    (if (plural) "system" else "person").takeIf { account && invite != "" }, device.trim())
                // a new system goes through setup next (F1.2–F1.4), which also names it
                if (account && plural) Setup.begin(ctx, dev.accountId, name.trim())
                chorus.adopt(dev)
            } catch (e: Exception) {
                error = e.message ?: "Couldn't reach the server."
            } finally { busy = false }
        }
    }

    // look at the invite as soon as it's a link (D-093's peek): the toggle follows it; a scanned
    // device link joins straight away
    LaunchedEffect(link) {
        invite = null
        if (Api.parseInvite(link) == null) return@LaunchedEffect
        delay(300)
        looking = true; error = null
        try {
            val k = Api.inviteKind(link)
            if (k == "system" || k == "person") plural = k == "system"
            invite = k ?: ""
            if (k == "device" && scanned) join()
        } catch (e: Exception) {
            error = e.message ?: "Couldn't reach the server."
        } finally { looking = false; scanned = false }
    }

    Box(Modifier.fillMaxSize()) {
        WelcomeBlobs()
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 38.dp).padding(bottom = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.weight(1f).heightIn(min = 40.dp))
            ChorusWordmark()
            Spacer(Modifier.weight(1f).heightIn(min = 40.dp))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                WelcomeField("Invite code", link, { link = it.trim(); error = null }, "chrs.…/i/…",
                    done = valid && invite != null, busy = looking, enabled = !busy, keyboard = KeyboardType.Uri,
                    shown = Welcoming.code(link), paste = {
                        ctx.getSystemService(ClipboardManager::class.java)?.primaryClip?.takeIf { it.itemCount > 0 }
                            ?.getItemAt(0)?.coerceToText(ctx)?.toString()?.trim()?.let { link = it }
                    })
                if (!forDevice) {
                    WelcomeField(if (plural) "System name" else "Your name", name, { name = it.take(100) },
                        if (plural) "e.g. The Lanterns" else "e.g. Alex", done = name.isNotBlank(), enabled = !busy,
                        capitalize = true)
                    WelcomeField("Handle", handle, { handle = Welcoming.handle(it) }, if (plural) "@lanterns" else "@alex",
                        done = handle.length >= 2, enabled = !busy, keyboard = KeyboardType.Ascii, prefix = if (handle.isEmpty()) null else "@")
                }
                WelcomeField("This device", device, { device = it.take(60) }, "Pixel 8", done = device.isNotBlank(), enabled = !busy)
                if (!forDevice && invite != "") Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    PluralToggle(plural, enabled = !busy) { plural = it }
                }
                error?.let {
                    Text(it, color = Color(0xFF9E2A1E), fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp)).background(Welcome.Cream).padding(horizontal = 12.dp, vertical = 8.dp))
                }
                Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    WelcomeButton("Link device", R.drawable.ic_scan, outlined = true, enabled = !busy) {
                        Welcoming.scan(ctx, onLink = { scanned = true; link = it }, onError = { error = it })
                    }
                    Spacer(Modifier.weight(1f))
                    WelcomeButton(if (forDevice) "Link" else "Continue", R.drawable.ic_arrow_right, enabled = !busy &&
                        Welcoming.ready(link, invite, name, handle, device), busy = busy, trailing = true, onClick = ::join)
                }
            }
        }
    }
}

/** F1.1's cream field: a small caps label over the text, a teal check once it's filled. */
@Composable
private fun WelcomeField(label: String, value: String, onChange: (String) -> Unit, placeholder: String, done: Boolean,
    enabled: Boolean, busy: Boolean = false, keyboard: KeyboardType = KeyboardType.Text, capitalize: Boolean = false,
    prefix: String? = null, shown: String? = null, paste: (() -> Unit)? = null) {
    var focused by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp)).background(Welcome.Cream.copy(alpha = .86f))
        .padding(start = 10.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label.uppercase(), color = Welcome.Ink, fontSize = 9.sp, lineHeight = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = .4.sp)
            Box {
                val style = TextStyle(color = Welcome.Ink, fontSize = 15.sp, fontFamily = HankenGrotesk)
                BasicTextField(value, onChange, Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }, enabled = enabled, singleLine = true,
                    textStyle = style.copy(color = if (shown != null && !focused) Color.Transparent else Welcome.Ink),
                    cursorBrush = SolidColor(Welcome.Ink),
                    keyboardOptions = KeyboardOptions(keyboardType = keyboard,
                        capitalization = if (capitalize) KeyboardCapitalization.Words else KeyboardCapitalization.None, autoCorrectEnabled = false),
                    decorationBox = { inner ->
                        Row {
                            if (prefix != null) Text(prefix, style = style)
                            Box(Modifier.weight(1f)) {
                                if (value.isEmpty()) Text(placeholder, style = style.copy(color = Welcome.Ink.copy(alpha = .45f), fontStyle = FontStyle.Italic))
                                inner()
                            }
                        }
                    })
                // a link shows as its code once it's been read (UI21 conflict 5)
                if (shown != null && !focused) Text(shown, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        when {
            busy -> CircularProgressIndicator(Modifier.size(18.dp), color = Welcome.Teal, strokeWidth = 2.dp)
            done -> Glyph(R.drawable.ic_check, Welcome.Teal, 20.dp)
            paste != null && value.isEmpty() -> Box(Modifier.size(36.dp).clip(RoundedCornerShape(8.dp))
                .clickable(role = Role.Button, onClick = paste).semantics { contentDescription = "Paste" }, contentAlignment = Alignment.Center) {
                Glyph(R.drawable.ic_copy, Welcome.Ink, 18.dp)
            }
        }
    }
}

/** "I am plural" / "I am **not** plural" and a cream switch (F1.1, F1.5). */
@Composable
private fun PluralToggle(on: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled, role = Role.Switch) { onChange(!on) }
        .padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(buildAnnotatedString {
            append("I am ")
            if (!on) withStyle(SpanStyle(color = Welcome.Sun, fontWeight = FontWeight.Bold)) { append("not ") }
            append("plural")
        }, color = Welcome.Cream, fontSize = 15.sp, fontFamily = HankenGrotesk, fontWeight = FontWeight.Medium)
        Box(Modifier.size(36.dp, 20.dp).clip(RoundedCornerShape(10.dp)).border(2.dp, Welcome.Cream, RoundedCornerShape(10.dp))
            .background(if (on) Welcome.Cream.copy(alpha = .0f) else Color.Transparent).padding(3.dp),
            contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart) {
            Box(Modifier.size(14.dp).clip(RoundedCornerShape(7.dp)).background(Welcome.Cream))
        }
    }
}

/** F1.1's pills: CONTINUE → in cream, LINK DEVICE outlined, ← BACK [plain] (F1.2). */
@Composable
internal fun WelcomeButton(label: String, icon: Int, outlined: Boolean = false, plain: Boolean = false, enabled: Boolean = true,
    busy: Boolean = false, trailing: Boolean = false, onClick: () -> Unit) {
    val ink = if (outlined || plain) Welcome.Cream else Welcome.Ink
    val shape = RoundedCornerShape(19.dp)
    Row(Modifier.heightIn(min = 38.dp).clip(shape)
        .then(when {
            plain -> Modifier
            outlined -> Modifier.border(2.dp, Welcome.Cream, shape)
            else -> Modifier.background(Welcome.Cream.copy(alpha = if (enabled) 1f else .6f))
        })
        .clickable(enabled = enabled && !busy, role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!trailing) Glyph(icon, ink, 14.dp)
        Text(label.uppercase(), color = ink.copy(alpha = if (enabled) 1f else .6f), fontSize = 14.sp, fontWeight = FontWeight.Bold,
            fontFamily = HankenGrotesk, letterSpacing = .3.sp)
        if (trailing) {
            if (busy) CircularProgressIndicator(Modifier.size(14.dp), color = ink, strokeWidth = 2.dp) else Glyph(icon, ink, 14.dp)
        }
    }
}
