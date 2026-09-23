package garden.vayne.chorus.ui

import android.content.Intent
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Link another device") },
        text = {
            when {
                link != null -> SelectionContainer { Text("Open this one-use link on the other device within a day:\n\n$link") }
                error != null -> Text(error.orEmpty())
                else -> Text("Making a link…")
            }
        },
        confirmButton = {
            if (link != null) TextButton(onClick = {
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, link)
                }, "Share Chorus invite"))
            }) { Text("Share") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}
