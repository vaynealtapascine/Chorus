package garden.vayne.chorus.data

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Inline reply from a chat notification (NOTIFICATIONS.md §7): the text becomes a `message.send`
 * op in the same channel, replying to the notified message, written as the current primary
 * fronter. It is queued like any other op, so it works offline and syncs later.
 */
object Reply {
    private const val TAG = "ChorusReply"
    const val KEY_TEXT = "reply_text"
    private const val EXTRA_SCOPE = "scope"
    private const val EXTRA_CHANNEL = "channel_id"
    private const val EXTRA_MESSAGE = "message_id"
    private const val EXTRA_NOTE = "note_id"
    private const val EXTRA_TITLE = "title"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Who speaks: the primary fronting member, else the first fronting member, else the only
     * active member (a person account's self member); `null` when nobody clearly fits.
     */
    fun speaker(model: Model): Member? {
        val fronting = model.current.filter { it.subjectType == "member" && it.level == "front" }
        val id = (fronting.firstOrNull { it.isPrimary } ?: fronting.firstOrNull())?.subjectId
        return id?.let { model.member(it) } ?: model.active.singleOrNull()
    }

    /** The payload for the reply op. */
    fun payload(channelId: String, replyTo: String?, author: String, text: String): JSONObject = JSONObject()
        .put("channel_id", channelId)
        .put("authors", JSONArray().put(author))
        .put("text", text)
        .put("entities", JSONArray())
        .apply { if (replyTo != null) put("reply_to", replyTo) }

    /** A "Reply" action for a message notification, or `null` if it lacks what a reply needs. */
    fun action(ctx: Context, p: JSONObject, noteId: Int): NotificationCompat.Action? {
        val space = p.optString("scope").takeIf { it.startsWith("space:") } ?: return null
        val channel = p.optString("channel_id").takeIf { it.isNotEmpty() } ?: return null
        val intent = Intent(ctx, ReplyReceiver::class.java)
            .putExtra(EXTRA_SCOPE, space).putExtra(EXTRA_CHANNEL, channel)
            .putExtra(EXTRA_MESSAGE, p.optString("message_id").takeIf { it.isNotEmpty() })
            .putExtra(EXTRA_NOTE, noteId).putExtra(EXTRA_TITLE, p.optString("title", "Chorus"))
        // the system writes the typed text into this intent, so it has to be mutable
        val pi = PendingIntent.getBroadcast(
            ctx, noteId, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val input = RemoteInput.Builder(KEY_TEXT).setLabel("Reply").build()
        return NotificationCompat.Action.Builder(android.R.drawable.ic_menu_send, "Reply", pi)
            .addRemoteInput(input).setAllowGeneratedReplies(false)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY).build()
    }

    fun onReceive(ctx: Context, intent: Intent, done: () -> Unit) {
        val app = ctx.applicationContext
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_TEXT)?.toString()?.trim()
        val space = intent.getStringExtra(EXTRA_SCOPE)
        val channel = intent.getStringExtra(EXTRA_CHANNEL)
        val noteId = intent.getIntExtra(EXTRA_NOTE, 0)
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Chorus"
        if (text.isNullOrEmpty() || space == null || channel == null) return done()
        scope.launch {
            val result = try {
                val chorus = Chorus.get(app)
                val model = chorus.awaitModel()
                val who = speaker(model)
                if (who == null) {
                    "Nobody is fronting; open Chorus to reply"
                } else {
                    val op = payload(channel, intent.getStringExtra(EXTRA_MESSAGE), who.id, text)
                    chorus.create("message.send", chorus.newId(), op, scope = space)
                    "Sent as ${who.shownName}"
                }
            } catch (e: Exception) {
                Log.w(TAG, "reply failed", e)
                "Couldn't send; open Chorus to reply"
            }
            try {
                // replace the notification so the reply spinner stops
                val n = NotificationCompat.Builder(app, Push.CHANNEL_MESSAGES)
                    .setSmallIcon(android.R.drawable.ic_popup_reminder)
                    .setContentTitle(title).setContentText(result)
                    .setOnlyAlertOnce(true).setAutoCancel(true)
                    .setTimeoutAfter(10_000)
                    .build()
                if (app.getSystemService(NotificationManager::class.java) != null &&
                    NotificationManagerCompat.from(app).areNotificationsEnabled()
                ) {
                    NotificationManagerCompat.from(app).notify(noteId, n)
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "can't update the notification", e)
            } finally {
                done()
            }
        }
    }
}

/** Receives inline replies (registered in the manifest, not exported). */
class ReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Reply.onReceive(context, intent) { pending.finish() }
    }
}
