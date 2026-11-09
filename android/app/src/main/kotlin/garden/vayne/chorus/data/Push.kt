package garden.vayne.chorus.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import com.google.firebase.messaging.FirebaseMessaging
import garden.vayne.chorus.MainActivity
import java.util.Base64
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * Push delivery on Android (NOTIFICATIONS.md §1, D-096):
 * - **FCM** (`fcm:<token>`) by default when built with Firebase and Google Play services gives a
 *   token, even if a UnifiedPush app is also installed on the phone.
 * - **UnifiedPush** (connector broadcasts, no library) on Google-free phones or when chosen in
 *   Settings → Advanced.
 * - **[NotifyCheck]** every few minutes when neither is available.
 *
 * Every payload is RFC 8291 ciphertext encrypted to this device's Web Push keys, so Google or the
 * UnifiedPush server only ever relays ciphertext.
 */
object Push {
    private const val TAG = "ChorusPush"
    private const val SETTING_STATE = "push"
    private const val SETTING_CHOICE = "push_choice"
    private const val DIST_REGISTER = "org.unifiedpush.android.distributor.REGISTER"
    private const val DIST_UNREGISTER = "org.unifiedpush.android.distributor.UNREGISTER"
    private const val DIST_ACK = "org.unifiedpush.android.distributor.MESSAGE_ACK"
    const val NEW_ENDPOINT = "org.unifiedpush.android.connector.NEW_ENDPOINT"
    const val MESSAGE = "org.unifiedpush.android.connector.MESSAGE"
    const val UNREGISTERED = "org.unifiedpush.android.connector.UNREGISTERED"
    const val REGISTRATION_FAILED = "org.unifiedpush.android.connector.REGISTRATION_FAILED"
    const val CHANNEL_SWITCHES = "switches"
    const val CHANNEL_MESSAGES = "messages"
    const val CHANNEL_POSTS = "posts"

    private const val REUPLOAD_INTERVAL_MS = 15 * 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val regMutex = Mutex()
    @Volatile private var lastUploadedEndpoint: String? = null
    @Volatile private var lastUploadedAt: Long = 0L

    data class Status(
        val delivery: String, // "fcm" | "distributor" | "polling"
        val distributor: String?,
        val fcmConfigured: Boolean,
        val distributors: List<String>,
        val choice: String, // "fcm" or "distributor:<pkg>"
    )

    internal sealed interface Target {
        data class Fcm(val token: String) : Target
        data class Distributor(val pkg: String) : Target
        data object None : Target
    }

    internal fun b64url(b: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    internal fun unb64(s: String): ByteArray = Base64.getUrlDecoder().decode(s.trim().trimEnd('='))

    /** Installed UnifiedPush distributors (package names). */
    fun distributors(ctx: Context): List<String> =
        ctx.packageManager.queryBroadcastReceivers(Intent(DIST_REGISTER), 0).map { it.activityInfo.packageName }.distinct()

    /** Human-readable name for an installed UnifiedPush distributor package. */
    fun distributorLabel(ctx: Context, pkg: String): String =
        runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString() }
            .getOrNull()?.takeIf { it.isNotBlank() }
            ?: if (pkg.startsWith("io.heckel.ntfy")) "ntfy" else pkg

    /**
     * Pick how this device registers: FCM by default whenever a token is available, unless the
     * user explicitly chose an installed UnifiedPush distributor (`distributor:<pkg>`).
     */
    internal fun selectTarget(
        fcmToken: String?,
        distributors: List<String>,
        currentDistributor: String?,
        choice: String?,
    ): Target {
        val chosenPkg = choice?.removePrefix("distributor:")?.takeIf { choice.startsWith("distributor:") && it in distributors }
        if (chosenPkg != null) return Target.Distributor(chosenPkg)
        if (!fcmToken.isNullOrBlank()) return Target.Fcm(fcmToken)
        val fallback = currentDistributor?.takeIf { it != Fcm.DISTRIBUTOR && it in distributors }
            ?: distributors.firstOrNull { it.startsWith("io.heckel.ntfy") }
            ?: distributors.singleOrNull()
        return if (fallback != null) Target.Distributor(fallback) else Target.None
    }

    /** Our registration state, kept in the encrypted store. */
    internal data class State(val token: String, val distributor: String, val endpoint: String?, val keys: WebPushCrypto.Keys) {
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

    private suspend fun load(chorus: Chorus): State? = chorus.setting(SETTING_STATE)?.let { runCatching { State.parse(it) }.getOrNull() }

    /** Whether a push endpoint is active, so pushes arrive (else [NotifyCheck] looks). */
    suspend fun registered(chorus: Chorus): Boolean = load(chorus)?.endpoint != null

    /** Current delivery state for Settings → Notifications. */
    suspend fun status(ctx: Context, chorus: Chorus): Status {
        val app = ctx.applicationContext
        val found = distributors(app)
        val s = load(chorus)
        val choice = chorus.setting(SETTING_CHOICE) ?: Fcm.DISTRIBUTOR
        val delivery = when {
            s?.endpoint == null -> "polling"
            s.distributor == Fcm.DISTRIBUTOR -> "fcm"
            else -> "distributor"
        }
        return Status(
            delivery = delivery,
            distributor = s?.distributor?.takeIf { it != Fcm.DISTRIBUTOR },
            fcmConfigured = Fcm.configured(app),
            distributors = found,
            choice = choice,
        )
    }

    /** Switch between `"fcm"` and `"distributor:<pkg>"` in Settings → Advanced. */
    suspend fun chooseMode(ctx: Context, choice: String): Status {
        val app = ctx.applicationContext
        val chorus = Chorus.get(app)
        chorus.putSetting(SETTING_CHOICE, choice)
        lastUploadedEndpoint = null
        ensureNow(app, forceUpload = true)
        return status(app, chorus)
    }

    /** Make sure this device is registered (called on app start/resume and after enrolment). */
    fun ensure(ctx: Context) {
        val app = ctx.applicationContext
        scope.launch { ensureNow(app, forceUpload = false) }
    }

    internal suspend fun ensureNow(ctx: Context, forceUpload: Boolean) {
        val app = ctx.applicationContext
        ensureChannels(app)
        val chorus = Chorus.get(app)
        chorus.awaitDevice() ?: return
        regMutex.withLock {
            val found = distributors(app)
            val current = load(chorus)
            val choice = chorus.setting(SETTING_CHOICE)
            val wantsExplicitDist = choice != null && choice.startsWith("distributor:") && choice.removePrefix("distributor:") in found
            val fcmToken = if (wantsExplicitDist) null else Fcm.token(app)
            when (val target = selectTarget(fcmToken, found, current?.distributor, choice)) {
                is Target.Fcm -> {
                    val outcome = registerFcmLocked(app, chorus, current, target.token, forceUpload)
                    if (outcome == UploadOutcome.Refused) {
                        // server doesn't offer FCM: fall back to an installed distributor or NotifyCheck
                        when (val fallback = selectTarget(null, found, current?.distributor, choice)) {
                            is Target.Distributor -> registerDistributorLocked(app, chorus, load(chorus), fallback.pkg, forceUpload)
                            else -> clearStaleLocked(app, chorus, load(chorus))
                        }
                    }
                }
                is Target.Distributor -> registerDistributorLocked(app, chorus, current, target.pkg, forceUpload)
                Target.None -> clearStaleLocked(app, chorus, current)
            }
        }
    }

    /** Called synchronously by [FcmService.onNewToken] so token rotation finishes before the service stops. */
    suspend fun onNewFcmToken(ctx: Context, token: String) {
        val app = ctx.applicationContext
        val chorus = Chorus.get(app)
        chorus.awaitDevice() ?: return
        regMutex.withLock {
            val found = distributors(app)
            val choice = chorus.setting(SETTING_CHOICE)
            if (choice != null && choice.startsWith("distributor:") && choice.removePrefix("distributor:") in found) return
            registerFcmLocked(app, chorus, load(chorus), token, forceUpload = true)
        }
    }

    /** Send a test push (`POST /devices/push/test`), self-healing once if the token went stale. */
    suspend fun sendTest(ctx: Context): String {
        val app = ctx.applicationContext
        if (!NotificationManagerCompat.from(app).areNotificationsEnabled()) {
            return "Notifications are turned off for Chorus in Android settings."
        }
        val chorus = Chorus.get(app)
        chorus.awaitDevice() ?: return "Not signed in."
        ensureNow(app, forceUpload = true)
        val s = load(chorus)
        if (s?.endpoint == null) {
            return "Instant push isn't active right now; Chorus checks every few minutes in the background instead."
        }
        return try {
            chorus.authedCall("POST", "/devices/push/test", JSONObject())
            "Test notification sent — it should arrive in a moment."
        } catch (e: ApiException) {
            if (e.code == "push_gone" && s.distributor == Fcm.DISTRIBUTOR) {
                refreshFcmToken(app)
                lastUploadedEndpoint = null
                ensureNow(app, forceUpload = true)
                return runCatching {
                    chorus.authedCall("POST", "/devices/push/test", JSONObject())
                    "Refreshed registration and sent a test notification."
                }.getOrElse { it.message ?: "Could not send a test notification." }
            }
            e.message ?: "Could not send a test notification."
        } catch (e: Exception) {
            e.message ?: "Could not reach the server."
        }
    }

    private suspend fun refreshFcmToken(ctx: Context) {
        if (!Fcm.configured(ctx)) return
        suspendCancellableCoroutine { cont ->
            FirebaseMessaging.getInstance().deleteToken().addOnCompleteListener { cont.resume(Unit) }
        }
    }

    private enum class UploadOutcome { Ok, Refused, TransientError }

    private suspend fun upload(chorus: Chorus, s: State, force: Boolean): UploadOutcome {
        val endpoint = s.endpoint ?: return UploadOutcome.Refused
        val now = System.currentTimeMillis()
        if (!force && endpoint == lastUploadedEndpoint && now - lastUploadedAt < REUPLOAD_INTERVAL_MS) {
            return UploadOutcome.Ok
        }
        return try {
            chorus.authedCall(
                "PUT", "/devices/push",
                JSONObject().put("endpoint", endpoint).put("p256dh", b64url(s.keys.publicRaw)).put("auth", b64url(s.keys.auth)),
            )
            lastUploadedEndpoint = endpoint
            lastUploadedAt = now
            UploadOutcome.Ok
        } catch (e: ApiException) {
            Log.w(TAG, "server refused push endpoint (${e.code}): ${e.message}")
            if (e.code == "bad_request") UploadOutcome.Refused else UploadOutcome.TransientError
        } catch (e: Exception) {
            Log.w(TAG, "couldn't upload push endpoint", e)
            UploadOutcome.TransientError
        }
    }

    private fun unregisterDistributorBroadcast(app: Context, state: State) {
        if (state.distributor == Fcm.DISTRIBUTOR) return
        runCatching {
            app.sendBroadcast(Intent(DIST_UNREGISTER).setPackage(state.distributor).putExtra("token", state.token))
        }
    }

    /** Register `fcm:<token>` on the server, unregistering any previous UnifiedPush distributor. */
    private suspend fun registerFcmLocked(
        app: Context,
        chorus: Chorus,
        current: State?,
        token: String,
        forceUpload: Boolean,
    ): UploadOutcome {
        if (current != null && current.distributor != Fcm.DISTRIBUTOR) {
            unregisterDistributorBroadcast(app, current)
        }
        val base = current?.takeIf { it.distributor == Fcm.DISTRIBUTOR }
            ?: State(UUID.randomUUID().toString(), Fcm.DISTRIBUTOR, null, WebPushCrypto.generate())
        val next = base.copy(endpoint = "fcm:$token")
        val changed = current?.endpoint != next.endpoint || current?.distributor != Fcm.DISTRIBUTOR
        val outcome = upload(chorus, next, force = forceUpload || changed)
        when (outcome) {
            UploadOutcome.Ok -> chorus.putSetting(SETTING_STATE, next.json())
            UploadOutcome.Refused -> {
                chorus.putSetting(SETTING_STATE, next.copy(endpoint = null).json())
                runCatching { chorus.authedCall("DELETE", "/devices/push") }
            }
            UploadOutcome.TransientError -> {
                // keep an already-uploaded FCM state across offline opens; otherwise save keys with empty endpoint
                if (changed) chorus.putSetting(SETTING_STATE, next.copy(endpoint = null).json())
            }
        }
        return outcome
    }

    private suspend fun registerDistributorLocked(
        app: Context,
        chorus: Chorus,
        current: State?,
        distributor: String,
        forceUpload: Boolean,
    ) {
        if (current != null && current.distributor != distributor) {
            unregisterDistributorBroadcast(app, current)
        }
        val state = if (current != null && current.distributor == distributor) {
            current
        } else {
            State(UUID.randomUUID().toString(), distributor, null, WebPushCrypto.generate())
        }
        chorus.putSetting(SETTING_STATE, state.json())
        val pi = PendingIntent.getBroadcast(
            app, 0, Intent("garden.vayne.chorus.PUSH_ID").setPackage(app.packageName), PendingIntent.FLAG_IMMUTABLE,
        )
        app.sendBroadcast(
            Intent(DIST_REGISTER).setPackage(distributor)
                .putExtra("token", state.token)
                .putExtra("application", app.packageName)
                .putExtra("pi", pi)
                .putExtra("message", "Chorus switch notifications"),
        )
        if (state.endpoint != null) upload(chorus, state, force = forceUpload)
    }

    private suspend fun clearStaleLocked(app: Context, chorus: Chorus, current: State?) {
        Log.i(TAG, "no FCM and no UnifiedPush distributor; NotifyCheck will poll in the background")
        if (current == null) return
        unregisterDistributorBroadcast(app, current)
        if (current.endpoint != null) {
            chorus.putSetting(SETTING_STATE, current.copy(endpoint = null).json())
            lastUploadedEndpoint = null
            runCatching { chorus.authedCall("DELETE", "/devices/push") }
        }
    }

    /** An FCM data message: the same RFC 8291 record a push app would have handed us. */
    suspend fun onFcm(ctx: Context, record: String) {
        val app = ctx.applicationContext
        val s = load(Chorus.get(app)) ?: return
        if (s.distributor != Fcm.DISTRIBUTOR) return
        val key = WebPushCrypto.privateFromPkcs8(s.keys.privatePkcs8)
        val plain = WebPushCrypto.decrypt(key, s.keys.publicRaw, s.keys.auth, unb64(record))
        if (plain == null) {
            Log.w(TAG, "fcm push didn't decrypt; refreshing registration")
            scope.launch { ensureNow(app, forceUpload = true) }
            return
        }
        show(app, JSONObject(String(plain)))
    }

    /** Broadcasts from a UnifiedPush distributor. */
    fun onReceive(ctx: Context, intent: Intent, done: () -> Unit) {
        val app = ctx.applicationContext
        val token = intent.getStringExtra("token") ?: return done()
        scope.launch {
            try { handle(app, intent, token) } finally { done() }
        }
    }

    private suspend fun handle(app: Context, intent: Intent, token: String) {
        val chorus = Chorus.get(app)
        when (intent.action) {
            NEW_ENDPOINT -> regMutex.withLock {
                val s = load(chorus) ?: return
                if (s.token != token || s.distributor == Fcm.DISTRIBUTOR) return
                val endpoint = intent.getStringExtra("endpoint") ?: return
                val next = s.copy(endpoint = endpoint)
                val outcome = upload(chorus, next, force = s.endpoint != endpoint)
                chorus.putSetting(
                    SETTING_STATE,
                    (if (outcome == UploadOutcome.Refused) next.copy(endpoint = null) else next).json(),
                )
            }
            UNREGISTERED, REGISTRATION_FAILED -> regMutex.withLock {
                val s = load(chorus) ?: return
                if (s.token != token || s.distributor == Fcm.DISTRIBUTOR) return
                chorus.putSetting(SETTING_STATE, s.copy(endpoint = null).json())
                lastUploadedEndpoint = null
                runCatching { chorus.authedCall("DELETE", "/devices/push") }
            }
            MESSAGE -> {
                val s = load(chorus) ?: return
                if (s.token != token || s.distributor == Fcm.DISTRIBUTOR) return
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

    private fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL_SWITCHES, "Switches", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_MESSAGES, "Mentions, replies and DMs", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_POSTS, "Post alerts", NotificationManager.IMPORTANCE_DEFAULT))
    }

    internal fun showAsync(ctx: Context, p: JSONObject, done: () -> Unit = {}) {
        scope.launch {
            try { show(ctx, p) } finally { done() }
        }
    }

    internal suspend fun show(ctx: Context, p: JSONObject) {
        val kind = p.optString("t")
        if (kind == "sync") {
            SyncWork.enqueue(ctx)
            scope.launch { runCatching { NotifyCheck.onTickle(ctx) } }
            return
        }
        ensureChannels(ctx)
        val isMessage = kind == "message"
        val highPriority = isMessage || kind == "test" || kind == "poll"
        val time = p.optJSONObject("time")
        val at = time?.optLong("at", 0L)?.takeIf { it > 0 && time.optString("precision") != "none" }
        val nm = ctx.getSystemService(NotificationManager::class.java)

        val n = if (isMessage) {
            val chorus = Chorus.get(ctx)
            val model = if (chorus.status.value == garden.vayne.chorus.data.Status.Loading && chorus.device != null) {
                kotlinx.coroutines.withTimeoutOrNull(1_500L) { runCatching { chorus.awaitModel() }.getOrNull() } ?: chorus.model.value
            } else {
                chorus.model.value
            }
            val spec = NotificationIcons.resolveSpec(p, model)
            val key = if (spec.channelId.isNotEmpty()) "chat:${spec.channelId}" else p.optString("message_id").ifEmpty { "chorus" }
            val noteId = key.hashCode()
            val openIntent = Intent(ctx, MainActivity::class.java)
                .setAction(MainActivity.ACTION_OPEN_CHAT)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .apply {
                    if (spec.channelId.isNotEmpty()) putExtra(MainActivity.EXTRA_OPEN_CHANNEL, spec.channelId)
                    spec.spaceId?.let { putExtra(MainActivity.EXTRA_OPEN_SPACE, it) }
                }
            val open = PendingIntent.getActivity(
                ctx, noteId, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val cleanIcon = NotificationIcons.renderChatIcon(ctx, chorus.device, spec)
            val shortcutId = NotificationIcons.publishConversationShortcut(ctx, spec, cleanIcon, openIntent)
            val largeIcon = if (shortcutId != null) cleanIcon else NotificationIcons.withChorusBadge(ctx, cleanIcon)

            val group = !spec.direct || !spec.chatTitle.equals(spec.senderName, ignoreCase = true)
            val self = Person.Builder().setName("You").build()
            val sender = Person.Builder()
                .setName(spec.senderName.ifBlank { "Someone" })
                .setKey("sender:${spec.senderName}")
                .build()
            val existing = nm?.activeNotifications?.firstOrNull { it.id == noteId }?.notification
                ?.let { NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(it) }
            val style = NotificationCompat.MessagingStyle(self)
                .setGroupConversation(group)
                .setConversationTitle(if (group) spec.chatTitle else null)
            existing?.messages?.takeLast(5)?.forEach { style.addMessage(it) }
            style.addMessage(p.optString("text"), at ?: System.currentTimeMillis(), sender)

            val builder = NotificationCompat.Builder(ctx, CHANNEL_MESSAGES)
                .setSmallIcon(garden.vayne.chorus.R.drawable.ic_stat_chorus)
                .setColor(NotificationIcons.BRAND_COLOR)
                .setContentTitle(p.optString("title", "Chorus"))
                .setContentText(p.optString("text"))
                .setLargeIcon(largeIcon)
                .setStyle(style)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setAutoCancel(true)
                .setContentIntent(open)
                .setGroup("messages:${spec.channelId}")
                .setWhen(at ?: System.currentTimeMillis())
                .setShowWhen(true)
            if (shortcutId != null) builder.setShortcutId(shortcutId)
            Reply.action(ctx, p, noteId)?.let { builder.addAction(it) }
            noteId to builder.build()
        } else {
            // post alerts (D-106): one per post, opening it; requests and answers open Friends;
            // a nudge to vote (D-136) rings as its post's alert or in its chat's channel
            val pollPost = p.optString("post_id").takeIf { kind == "poll" && it.isNotEmpty() }
            val alert = kind == "post" || kind == "alert" || pollPost != null
            val key = when (kind) {
                "post" -> "post:${p.optString("post_id")}"
                "alert" -> "alert:${p.optString("member_id")}:${p.optString("account_id")}"
                "poll" -> "poll:${pollPost ?: p.optString("message_id")}"
                else -> p.optString("account_id").ifEmpty { kind.ifEmpty { "chorus" } }
            }
            val noteId = key.hashCode()
            val tap = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP).apply {
                if (kind == "post") putExtra(MainActivity.EXTRA_OPEN_POST, p.optString("post_id"))
                if (kind == "alert") putExtra(MainActivity.EXTRA_OPEN_PEOPLE, true)
                if (kind == "poll") {
                    if (pollPost != null) putExtra(MainActivity.EXTRA_OPEN_POST, pollPost)
                    else setAction(MainActivity.ACTION_OPEN_CHAT).putExtra(MainActivity.EXTRA_OPEN_CHANNEL, p.optString("channel_id"))
                }
            }
            val open = PendingIntent.getActivity(
                ctx,
                noteId,
                tap,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val builder = NotificationCompat.Builder(ctx, if (alert) CHANNEL_POSTS else if (highPriority) CHANNEL_MESSAGES else CHANNEL_SWITCHES)
                .setSmallIcon(garden.vayne.chorus.R.drawable.ic_stat_chorus)
                .setColor(NotificationIcons.BRAND_COLOR)
                .setContentTitle(p.optString("title", "Chorus"))
                .setContentText(p.optString("text"))
                .setAutoCancel(true)
                .setContentIntent(open)
                .setGroup(if (alert) "posts" else "switches:$key")
                // the shown time is the fuzzed one the follower may know, never the real switch time
                .apply { if (at != null) setWhen(at).setShowWhen(true) else setShowWhen(false) }
            noteId to builder.build()
        }
        if (NotificationManagerCompat.from(ctx).areNotificationsEnabled()) {
            runCatching { NotificationManagerCompat.from(ctx).notify(n.first, n.second) }
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
