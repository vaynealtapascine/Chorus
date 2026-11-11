package garden.vayne.chorus.data

import android.content.Context
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

/** The rules of the one-screen welcome (F1.1/F1.5) and its QR scan. */
object Welcoming {
    /** What the person can type as a handle: lowercase letters, digits and `_`, at most 32 (D-126). */
    fun handle(typed: String): String =
        typed.removePrefix("@").lowercase().filter { it in 'a'..'z' || it in '0'..'9' || it == '_' }.take(32)

    /** The invite's code, shown in place of a pasted link once it's been read. */
    fun code(link: String): String? = Api.parseInvite(link)?.code?.takeIf { it != link.trim() }

    /**
     * Ready to join: a link the server has looked at ([invite] not null), a name for a new
     * account (or an older server's), a handle that's empty or at least two characters, and a name
     * for this device.
     */
    fun ready(link: String, invite: String?, name: String, handle: String, device: String): Boolean =
        Api.parseInvite(link) != null && invite != null && device.isNotBlank() &&
            (invite == "device" || (name.isNotBlank() && (handle.isEmpty() || handle.length >= 2)))

    /**
     * Scan a Link a device QR with Google's code scanner (no camera permission: Play services
     * shows the camera). A code that isn't a Chorus link is reported, not used.
     */
    fun scan(ctx: Context, onLink: (String) -> Unit, onError: (String) -> Unit) {
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        runCatching {
            GmsBarcodeScanning.getClient(ctx, options).startScan()
                .addOnSuccessListener { code ->
                    val text = code.rawValue.orEmpty().trim()
                    if (Api.parseInvite(text) != null) onLink(text) else onError("That code isn't a Chorus link.")
                }
                .addOnFailureListener { onError(it.message ?: "The camera couldn't open.") }
        }.onFailure { onError(it.message ?: "The camera couldn't open.") }
    }
}
