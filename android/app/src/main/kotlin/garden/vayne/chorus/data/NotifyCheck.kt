package garden.vayne.chorus.data

import android.app.ActivityManager
import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/**
 * Notifications for a phone without push (NOTIFICATIONS.md §1): every few minutes, ask the server
 * what it delivered since the last look (`GET /notifications?after=`) and show it the way a push
 * would be shown. The server has already filtered, delayed and fuzzed each one, so this shows
 * exactly what a push would have carried, only later. Skipped while push works.
 */
object NotifyCheck {
    private const val CURSOR = "notify_after"

    /** One look. The first one only notes where "new" starts, so history doesn't pour in. */
    suspend fun run(ctx: Context) {
        val chorus = Chorus.get(ctx.applicationContext)
        val dev = chorus.device ?: return
        if (Push.registered(chorus)) return
        val cursor = chorus.setting(CURSOR)?.toLongOrNull()
        val items = Api.call("GET", dev.base, "/notifications?after=${cursor ?: 0}", null, dev.session)
            .optJSONArray("items") ?: return
        val fresh = (0 until items.length()).map { items.getJSONObject(it) }.sortedBy { it.optLong("delivered_at") }
        val newest = fresh.maxOfOrNull { it.optLong("delivered_at") } ?: return
        if (cursor != null && !inForeground()) fresh.mapNotNull(::asPush).forEach { Push.show(ctx, it) }
        chorus.putSetting(CURSOR, newest.toString())
    }

    /** A listed notification as the push payload [Push.show] knows. */
    internal fun asPush(i: JSONObject): JSONObject? = when (i.optString("kind")) {
        "switch" -> {
            val account = i.optJSONObject("account")
            JSONObject().put("t", "switch").put("id", i.optString("id"))
                .put("account_id", account?.optString("id").orEmpty())
                .put("title", account?.let { a -> a.text("display_name") ?: a.text("handle")?.let { "@$it" } } ?: "Chorus")
                .put("text", i.optString("text")).put("time", i.optJSONObject("time") ?: JSONObject())
        }
        "mention", "dm", "reply", "message", "member_dm" -> JSONObject().put("t", "message").put("id", i.optString("id"))
            .put("title", i.text("title") ?: "Chorus").put("text", i.optString("text"))
            .put("channel_id", i.optString("channel_id")).put("message_id", i.optString("message_id"))
            .put("scope", i.optString("scope")).apply { i.text("reply_as")?.let { put("reply_as", it) } }
        // own switches from another device: shown like a followed account's switch, keyed apart
        "own_switch" -> JSONObject().put("t", "switch").put("id", i.optString("id")).put("account_id", "own")
            .put("title", i.text("title") ?: "Chorus").put("text", i.optString("text"))
        else -> null
    }

    private fun JSONObject.text(key: String): String? = optString(key).takeIf { has(key) && !isNull(key) && it.isNotBlank() }

    /** In the app, Chorus shows new things itself. */
    private fun inForeground(): Boolean {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    /** Start the chain (app start); a chain already waiting is kept. */
    fun start(ctx: Context) = schedule(ctx, 0, ExistingWorkPolicy.KEEP)

    internal fun schedule(ctx: Context, delayMinutes: Long, policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<NotifyCheckWork>()
            .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("notify-check", policy, request)
    }
}

/**
 * One look, then the next in five minutes. Periodic work can't run more often than every 15
 * minutes, so each run books the next; a dozing phone still batches them into its own windows.
 */
class NotifyCheckWork(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        try {
            NotifyCheck.run(applicationContext)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (_: Exception) {
            // offline or the server is away: the next look tries again
        } finally {
            // appended: replacing would cancel this very run
            NotifyCheck.schedule(applicationContext, 5, ExistingWorkPolicy.APPEND_OR_REPLACE)
        }
        return Result.success()
    }
}
