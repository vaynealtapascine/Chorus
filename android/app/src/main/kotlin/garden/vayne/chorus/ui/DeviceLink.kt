package garden.vayne.chorus.ui

import android.content.Intent
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import garden.vayne.chorus.designsystem.LocalChorusPalette
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import garden.vayne.chorus.data.Chorus

/** The server returns a one-use, one-day invite; the owner can copy or share it. */
@Composable
fun DeviceLink(chorus: Chorus, onClose: () -> Unit) {
    val context = LocalContext.current
    var link by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        try { link = chorus.deviceInvite() } catch (e: Exception) { error = e.message ?: "Could not create a link." }
    }
    Drawer(onClose, "Link another device", footer = {
        if (link != null) DrawerButton("Share link", {
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, link)
            }, "Share Chorus invite"))
        })
        DrawerButton("Close", onClose, ButtonKind.Quiet)
    }) {
        when {
            link != null -> {
                FormNote("Works once, for one day.",
                    garden.vayne.chorus.designsystem.R.drawable.ic_lock)
                FormCard { SelectionContainer { Text(link.orEmpty(), color = LocalChorusPalette.current.ink, fontSize = 14.sp,
                    modifier = Modifier.padding(16.dp)) } }
            }
            error != null -> FormNote(error.orEmpty(), warning = true)
            else -> Text("Making a link…", color = LocalChorusPalette.current.ink2)
        }
    }
}
