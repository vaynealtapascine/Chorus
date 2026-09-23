package garden.vayne.chorus.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import garden.vayne.chorus.MainActivity
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * UnifiedPush without a library (NOTIFICATIONS.md §1): the connector side of the distributor
 * protocol is a handful of broadcasts. The distributor is normally the ntfy app pointed at the
 * owner's ntfy server; it hands us an endpoint, we give the server that endpoint plus our Web Push
 * keys, and pushes arrive here as RFC 8291 ciphertext that only this device can read.
 */
object Push {
    private const val TAG = "ChorusPush"
    private const val DIST_REGISTER = "org.unifiedpush.android.distributor.REGISTER"
    private const val DIST_UNREGISTER = "org.unifiedpush.android.distributor.UNREGISTER"
    private const val DIST_ACK = "org.unifiedpush.android.distributor.MESSAGE_ACK"
    const val NEW_ENDPOINT = "org.unifiedpush.android.connector.NEW_ENDPOINT"
    const val MESSAGE = "org.unifiedpush.android.connector.MESSAGE"
    const val UNREGISTERED = "org.unifiedpush.android.connector.UNREGISTERED"
    const val REGISTRATION_FAILED = "org.unifiedpush.android.connector.REGISTRATION_FAILED"
    const val CHANNEL_SWITCHES = "switches"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun b64url(b: ByteArray) = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    private fun unb64(s: String) = Base64.decode(s, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    /** Installed UnifiedPush distributors (package names). */
    fun distributors(ctx: Context): List<String> =
        ctx.packageManager.queryBroadcastReceivers(Intent(DIST_REGISTER), 0).map { it.activityInfo.packageName }.distinct()

    /** Our registration state, kept in the encrypted store. */
    private data class State(val token: String, val distributor: String, val endpoint: String?, val keys: WebPushCrypto.Keys) {
        fun json(): String = JSONObject()
            .put("token", token).put("distributor", distributor).put("endpoint", endpoint ?: JSONObject.NULL)
            .put("priv", b64url(keys.privatePkcs8)).put("pub", b64url(keys.publicRaw)).put("auth", b64url(keys.auth))
            .toString()

        companion object {
            fun parse(s: String): State = JSONObject(s).let {
                State(
                    it.getString("token"), it.getString("distributor"),
                    if (it.isNull("endpoint")) null else it.getString("endpoint"),
                    WebPushCrypto.Keys(unb64(it.getString("priv")), unb64(it.getString("pub")), unb64(it.getString("auth"))),
                )
            }
        }
    }

    private suspend fun load(chorus: Chorus): State? = chorus.setting("push")?.let { runCatching { State.parse(it) }.getOrNull() }

    /**
     * Make sure this device is registered (call on app start once signed in). Picks the ntfy app if
     * present, else the only distributor. Does nothing when none is installed.
     */
    fun ensure(ctx: Context) {
        val app = ctx.applicationContext
        scope.launch {
            val chorus = Chorus.get(app)
            chorus.awaitDevice() ?: return@launch
            val found = distributors(app)
            val current = load(chorus)
            val distributor = current?.distributor?.takeIf { it in found }
                ?: found.firstOrNull { it.startsWith("io.heckel.ntfy") }
                ?: found.singleOrNull()
            if (distributor == null) {
                Log.i(TAG, "no UnifiedPush distributor installed (install ntfy to get switch notifications)")
                return@launch
            }
            val state = if (current != null && current.distributor == distributor) {
                current
            } else {
                State(UUID.randomUUID().toString(), distributor, null, WebPushCrypto.generate())
            }
            chorus.putSetting("push", state.json())
            // registering again is harmless: the distributor answers with the same endpoint
            val pi = PendingIntent.getBroadcast(app, 0, Intent("garden.vayne.chorus.PUSH_ID").setPackage(app.packageName), PendingIntent.FLAG_IMMUTABLE)
            app.sendBroadcast(
                Intent(DIST_REGISTER).setPackage(distributor)
                    .putExtra("token", state.token)
                    .putExtra("application", app.packageName)
                    .putExtra("pi", pi)
                    .putExtra("message", "Chorus switch notifications"),
            )
            if (state.endpoint != null) upload(chorus, state)
        }
    }

    private suspend fun upload(chorus: Chorus, s: State) {
        val dev = chorus.awaitDevice() ?: return
        val endpoint = s.endpoint ?: return
        runCatching {
            Api.call(
                "PUT", dev.base, "/devices/push",
                JSONObject().put("endpoint", endpoint).put("p256dh", b64url(s.keys.publicRaw)).put("auth", b64url(s.keys.auth)),
                dev.session,
            )
        }.onFailure { Log.w(TAG, "couldn't register the push endpoint with the server", it) }
    }

    /** Broadcasts from the distributor. */
    fun onReceive(ctx: Context, intent: Intent, done: () -> Unit) {
        val app = ctx.applicationContext
        val token = intent.getStringExtra("token") ?: return done()
        scope.launch {
            try { handle(app, intent, token) } finally { done() }
        }
    }

    private suspend fun handle(app: Context, intent: Intent, token: String) {
        run {
            val chorus = Chorus.get(app)
            val s = load(chorus) ?: return
            if (s.token != token) return // not ours (or an old registration)
            when (intent.action) {
                NEW_ENDPOINT -> {
                    val endpoint = intent.getStringExtra("endpoint") ?: return
                    val next = s.copy(endpoint = endpoint)
                    chorus.putSetting("push", next.json())
                    upload(chorus, next)
                }
                UNREGISTERED, REGISTRATION_FAILED -> {
                    chorus.putSetting("push", s.copy(endpoint = null).json())
                    val dev = chorus.awaitDevice() ?: return
                    runCatching { Api.call("DELETE", dev.base, "/devices/push", null, dev.session) }
                }
                MESSAGE -> {
                    val bytes = intent.getByteArrayExtra("bytesMessage")
                        ?: intent.getStringExtra("message")?.toByteArray()
                        ?: return
                    intent.getStringExtra("id")?.let { id ->
                        app.sendBroadcast(Intent(DIST_ACK).setPackage(s.distributor).putExtra("token", token).putExtra("id", id))
                    }
                    val key = WebPushCrypto.privateFromPkcs8(s.keys.privatePkcs8)
                    val plain = WebPushCrypto.decrypt(key, s.keys.publicRaw, s.keys.auth, bytes)
                    if (plain == null) {
                        Log.w(TAG, "push didn't decrypt; ignoring")
                        return
                    }
                    show(app, JSONObject(String(plain)))
                }
            }
        }
    }

    private fun show(ctx: Context, p: JSONObject) {
        if (p.optString("t") == "sync") {
            SyncWork.enqueue(ctx)
            return
        }
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_SWITCHES, "Switches", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE,
        )
        val time = p.optJSONObject("time")
        val at = time?.optLong("at", 0L)?.takeIf { it > 0 && time.optString("precision") != "none" }
        val n = NotificationCompat.Builder(ctx, CHANNEL_SWITCHES)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(p.optString("title", "Chorus"))
            .setContentText(p.optString("text"))
            .setAutoCancel(true)
            .setContentIntent(open)
            .setGroup("switches:" + p.optString("account_id"))
            // the shown time is the fuzzed one the follower may know, never the real switch time
            .apply { if (at != null) setWhen(at).setShowWhen(true) else setShowWhen(false) }
            .build()
        if (NotificationManagerCompat.from(ctx).areNotificationsEnabled()) {
            runCatching { NotificationManagerCompat.from(ctx).notify(p.optString("account_id").hashCode(), n) }
        }
    }
}

/** Receives the distributor's broadcasts (registered in the manifest). */
class PushReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Push.onReceive(context, intent) { pending.finish() }
    }
}
