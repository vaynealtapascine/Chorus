package garden.vayne.chorus.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import garden.vayne.chorus.data.Chorus
import garden.vayne.chorus.data.DeviceInvite
import garden.vayne.chorus.data.DeviceLinks
import garden.vayne.chorus.data.LinkedDevice
import garden.vayne.chorus.designsystem.LocalChorusPalette
import garden.vayne.chorus.designsystem.R
import kotlinx.coroutines.launch

/**
 * F1.6 Link a device: a one-use link as a QR code to scan from the other device's welcome screen,
 * Copy link, and this account's devices, each but this one with Sign out.
 */
@Composable
internal fun LinkDeviceScreen(chorus: Chorus, onBack: () -> Unit) {
    val p = LocalChorusPalette.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var invite by remember { mutableStateOf<DeviceInvite?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var devices by remember { mutableStateOf<List<LinkedDevice>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var signingOut by remember { mutableStateOf<LinkedDevice?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(attempt) {
        invite = null; error = null; copied = false
        try { invite = chorus.deviceInvite() } catch (e: Exception) { error = e.message ?: "Could not make a link." }
    }
    LaunchedEffect(reload) { devices = runCatching { chorus.linkedDevices() }.getOrElse { devices } }
    Column(Modifier.fillMaxSize().background(p.bg)) {
        SubpageBar("Link a device", null, onBack) { BarIcon(R.drawable.ic_more_vert, "More", p.ink) { menu = true } }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(cardColor()).padding(20.dp), contentAlignment = Alignment.Center) {
                val qr = invite?.qr
                when {
                    qr != null -> QrCode(qr, Modifier.size(220.dp))
                    error != null -> Text(error.orEmpty(), color = p.danger, fontSize = 14.sp)
                    else -> Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(28.dp), color = p.accent, strokeWidth = 2.dp)
                    }
                }
            }
            Text("Works once, for one day.", color = p.ink2, fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DrawerButton(if (copied) "Copied" else "Copy link", {
                    invite?.url?.let { url ->
                        ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Chorus link", url))
                        copied = true
                    }
                }, enabled = invite != null, modifier = Modifier.width(140.dp))
                if (error != null) DrawerButton("Try again", { attempt++ }, ButtonKind.Neutral, modifier = Modifier.width(140.dp))
            }
            devices?.takeIf { it.isNotEmpty() }?.let { list ->
                SectionLabel("Devices")
                for (d in list) DeviceRow(d) { signingOut = d }
            }
        }
    }
    MenuDrawer(menu, { menu = false }) {
        MenuRow("Share link", R.drawable.ic_share, enabled = invite != null) {
            menu = false
            invite?.url?.let { url ->
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url), null))
            }
        }
        MenuRow("New link", R.drawable.ic_refresh) { menu = false; attempt++ }
    }
    signingOut?.let { d ->
        ConfirmDrawer("Sign out ${d.name}?", null, "Sign out", danger = true, busy = busy, icon = R.drawable.ic_monitor, onConfirm = {
            busy = true
            scope.launch {
                try { chorus.revokeDevice(d.id); signingOut = null; reload++ }
                catch (e: Exception) { error = e.message ?: "Could not sign it out." }
                finally { busy = false }
            }
        }, onDismiss = { signingOut = null })
    }
}

@Composable
private fun DeviceRow(d: LinkedDevice, onSignOut: () -> Unit) {
    val p = LocalChorusPalette.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(cardColor()).heightIn(min = 70.dp).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(40.dp).background(p.accent, CircleShape), contentAlignment = Alignment.Center) {
            Text(d.name.take(1).uppercase(), color = p.onAccent, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
        Column(Modifier.weight(1f)) {
            Text(d.name, color = p.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val line = when {
                d.current -> "This device"
                d.lastSeen != null -> ago(d.lastSeen).let { if (it == "just now") "Last seen just now" else "Last seen $it ago" }
                else -> null
            }
            line?.let { Text(it, color = p.ink3, fontSize = 13.sp) }
        }
        if (!d.current) Text("SIGN OUT", color = p.ink2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onSignOut).padding(8.dp))
    }
}

/** The server's QR (`qr_svg`) as dark modules on white, whatever the theme, so any camera reads it. */
@Composable
internal fun QrCode(qr: DeviceLinks.Qr, modifier: Modifier = Modifier) {
    Canvas(modifier.clip(RoundedCornerShape(8.dp)).background(Color.White).semantics { contentDescription = "QR code" }) {
        val cell = size.minDimension / qr.size
        for ((x, y, w) in qr.runs) drawRect(Color.Black, Offset(x * cell, y * cell), Size(w * cell + .5f, cell + .5f))
    }
}
