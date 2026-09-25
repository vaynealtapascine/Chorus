package garden.vayne.chorus

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat

/** Content handed to Chorus by Android's share sheet. It is never sent without a composer review. */
data class SharedDraft(val id: Long, val text: String, val uris: List<Uri>) {
    companion object {
        fun from(intent: Intent?, id: Long): SharedDraft? {
            if (intent?.action != Intent.ACTION_SEND && intent?.action != Intent.ACTION_SEND_MULTIPLE) return null
            val uris = LinkedHashSet<Uri>()
            if (intent.action == Intent.ACTION_SEND)
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let(uris::add)
            else
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let(uris::addAll)
            intent.clipData?.let { clips ->
                for (index in 0 until clips.itemCount) clips.getItemAt(index).uri?.let(uris::add)
            }
            val textItems = ArrayList<String>()
            runCatching { intent.getCharSequenceExtra(Intent.EXTRA_TEXT) }.getOrNull()?.toString()?.let(textItems::add)
            runCatching { intent.getCharSequenceArrayListExtra(Intent.EXTRA_TEXT) }.getOrNull()
                ?.forEach { textItems.add(it.toString()) }
            intent.clipData?.let { clips ->
                for (index in 0 until clips.itemCount) clips.getItemAt(index).text?.toString()?.let(textItems::add)
            }
            val text = combineSharedText(textItems)
            if (text.isBlank() && uris.isEmpty()) return null
            return SharedDraft(id, text, uris.toList())
        }
    }
}

/** Sharesheets often repeat EXTRA_TEXT in ClipData; keep distinct pieces in sender order. */
internal fun combineSharedText(parts: List<String>): String = parts.filter { it.isNotBlank() }.distinct().joinToString("\n")
