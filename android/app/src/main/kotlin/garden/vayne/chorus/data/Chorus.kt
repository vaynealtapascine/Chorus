package garden.vayne.chorus.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import java.security.SecureRandom
import java.io.File
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import uniffi.chorus_ffi.CoreReplica
import uniffi.chorus_ffi.newId

/** [SignedOut]: the server refused this device (signed out from another one, or its key no longer
 * matches; D-148, D-153). The app shows onboarding; what it still holds is hidden, kept until the
 * server's `wipe_at` and then erased (D-152), or picked back up by a device link of its account. */
enum class Status { Loading, NoDevice, Offline, Connecting, Live, SignedOut, StorageError }

data class RecheckProgress(val checked: Int, val total: Int)

/** A permanently refused local op; core keeps the payload until the owner dismisses it. */
data class SyncIssue(val id: String, val kind: String, val at: Long, val code: String,
    val message: String, val text: String, val scope: String = "", val entityId: String? = null,
    val memberId: String? = null, val payload: JSONObject = JSONObject())

data class MessageRevision(val rev: Int, val at: Long, val original: Boolean, val text: String,
    val contentWarning: String?)

/**
 * The app's one sync client (CLIENTS.md §2.3): owns the core replica, the sync socket and the
 * on-disk store. Durable protocol logic is in chorus-core; ephemeral typing is decoded here —
 * the Android twin of web/src/lib/sync/client.ts.
 *
 * Threading: the replica is internally locked; persistence and model rebuilds run on one
 * background thread, in order. The socket's lifecycle (status, socket, backoff, renewal) is one
 * [SyncLink.State], changed only through [transition] under [linkLock], from either thread.
 */
class Chorus private constructor(private val ctx: Context) {
    // replaced by a fresh one after an erase (D-152); store thread
    @Volatile private var store = Store(ctx)
    val editorDrafts = EditorDraftStore({ store.editorDraft(it) }, { k, v -> store.put(k, v) }, { store.remove(it) })
    val editorOwner: String get() = device?.let { "${it.base}|${it.accountId}" }.orEmpty()
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "chorus-store") }
    private val dispatcher = worker.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val main = Handler(Looper.getMainLooper())
    private val random = SecureRandom()

    private val _status = MutableStateFlow(Status.Loading)
    val status: StateFlow<Status> = _status
    // the connection banner (F2.5, F10.3): sockets lost or refused since the last welcome, and
    // whether Android has a network at all
    private val _connectFailures = MutableStateFlow(0)
    val connectFailures: StateFlow<Int> = _connectFailures
    private val _network = MutableStateFlow(true)
    val network: StateFlow<Boolean> = _network
    private val _model = MutableStateFlow(Model.Empty)
    val model: StateFlow<Model> = _model
    private val _frontHistoryRevision = MutableStateFlow(0L)
    val frontHistoryRevision: StateFlow<Long> = _frontHistoryRevision
    private val _typing = MutableStateFlow<Map<String, List<TypingEntry>>>(emptyMap())
    val typing: StateFlow<Map<String, List<TypingEntry>>> = _typing
    private var typingExpiry: Job? = null
    private var typingOffset = 0L
    private val typingThrottle = TypingThrottle()
    private var typingNames = TypingNames()
    private var frontHistories = NamedHistoryCache() // store thread only
    private val _keepEverything = MutableStateFlow(true)
    val keepEverything: StateFlow<Boolean> = _keepEverything
    private val _fileProgress = MutableStateFlow<OfflineFiles.Progress?>(null)
    val fileProgress: StateFlow<OfflineFiles.Progress?> = _fileProgress
    private val _syncIssues = MutableStateFlow<List<SyncIssue>>(emptyList())
    val syncIssues: StateFlow<List<SyncIssue>> = _syncIssues
    private val _fixedIssues = MutableStateFlow<List<SyncIssues.Fixed>>(emptyList())
    val fixedIssues: StateFlow<List<SyncIssues.Fixed>> = _fixedIssues
    // when this device last caught up with every scope (F9.6 "Last synced 2 min ago")
    private val _lastSynced = MutableStateFlow<Long?>(null)
    val lastSynced: StateFlow<Long?> = _lastSynced
    private val _heldMessages = MutableStateFlow<Map<String, HeldMessage>>(emptyMap())
    val heldMessages: StateFlow<Map<String, HeldMessage>> = _heldMessages
    private var heldTimer: Job? = null // store thread only
    private val _recheckProgress = MutableStateFlow<RecheckProgress?>(null)
    val recheckProgress: StateFlow<RecheckProgress?> = _recheckProgress
    private val fillMutex = Mutex()
    private var fillStarted = false // once per process session
    private var recheckWatch: ((String) -> Unit)? = null // store thread only
    private var recheckDone: CompletableDeferred<Unit>? = null // store thread only

    @Volatile var device: DeviceRecord? = null
        private set
    // while signed out: when, and when what is kept here is erased (D-152)
    private val _signedOut = MutableStateFlow<SignedOutRecord?>(null)
    val signedOutRecord: StateFlow<SignedOutRecord?> = _signedOut
    @Volatile private var replica: CoreReplica? = null
    @Volatile private var ws: WebSocket? = null // written under linkLock
    private val linkLock = Any()
    private var link = SyncLink.State() // guarded by linkLock
    private var wsNumber = 0L // guarded by linkLock: which of link's sockets [ws] is
    // before init: the store thread may start transitions while the constructor runs
    private val retry = Runnable { transition(SyncLink.Event.RetryDue) }
    private val rebuildPending = AtomicBoolean(false)
    private val syncReady = MutableStateFlow(false)
    private val expectedScopes = mutableSetOf<String>() // store thread only
    private val caughtScopes = mutableSetOf<String>() // store thread only
    private var foreground = false // main thread only
    private var workLeases = 0 // main thread only

    val accountScope: String get() = "account:${device?.accountId.orEmpty()}"

    /** Live and caught up with every scope. */
    val upToDate: StateFlow<Boolean> get() = syncReady

    /** Signed out from another device (D-153): onboarding shows, nothing syncs, the copy is hidden. */
    val signedOut: Boolean get() = _status.value == Status.SignedOut

    /** The account of the copy a signed-out phone still holds (D-153), or null. */
    val keptAccount: String? get() = if (signedOut) device?.accountId else null

    init {
        scope.launch {
            try { start() } catch (e: Exception) { storageFailed(e) }
        }
        scope.launch {
            syncReady.collect { ready ->
                if (!ready) return@collect
                val now = System.currentTimeMillis()
                _lastSynced.value = now
                runCatching { store.put(LAST_SYNCED, now.toString()) }
            }
        }
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        _network.value = cm?.activeNetwork != null
        cm?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { _network.value = true; main.post { reconnectNow() } }
            override fun onLost(network: Network) { _network.value = false }
        })
    }

    private fun start() {
        // an erase cut short goes on first; a kept copy whose time is up goes now (D-152)
        if (Erase.pending(ctx)) erase(Erase.Reason.Resumed)
        val kept = store.get(SIGNED_OUT)?.let(SignedOutRecord::fromJson)
        if (kept != null && kept.due(System.currentTimeMillis())) erase(Erase.Reason.WipeAtPassed)
        frontHistories = NamedHistoryCache()
        clearTyping()
        typingNames = TypingNames()
        heldTimer?.cancel()
        heldTimer = null
        _heldMessages.value = emptyMap()
        _keepEverything.value = store.get("setting:keep_everything") != "false"
        _lastSynced.value = store.get(LAST_SYNCED)?.toLongOrNull()
        _fixedIssues.value = SyncIssues.parseFixed(store.get(SyncIssues.KEY))
        val dev = store.get("device")?.let { runCatching { DeviceRecord.fromJson(it) }.getOrNull() }
        device = dev
        typingNames = TypingNames(dev?.accountId.orEmpty())
        Pins.trust(dev?.pin)
        if (dev == null) {
            transition(SyncLink.Event.Loaded(device = false, signedOut = false))
            return
        }
        replica = CoreReplica.restore(dev.deviceId, dev.node, store.get("meta").orEmpty(), store.opsJson(), store.get("hlc").orEmpty())
        refreshIssues(replica!!)
        refreshHeld(replica!!)
        val record = store.get(SIGNED_OUT)?.let(SignedOutRecord::fromJson)
        _signedOut.value = record
        // the deadline survives a reboot or a cleared job queue: book it again
        record?.delay(System.currentTimeMillis())?.let { WipeWork.schedule(ctx, it, androidx.work.ExistingWorkPolicy.KEEP) }
        transition(SyncLink.Event.Loaded(device = true, signedOut = record != null))
        rebuild()
    }

    /** After enrolment. */
    fun adopt(dev: DeviceRecord) {
        scope.launch {
            try {
                store.put("device", dev.toJson())
                // linked to the kept copy's account again (D-153): it carries on, and what was
                // written here and never sent goes out under the new device
                store.remove(SIGNED_OUT)
                WipeWork.cancel(ctx)
                start()
                // app start registered nothing while signed out: do it now, not at the next launch
                Push.ensure(ctx)
            } catch (e: Exception) { storageFailed(e) }
        }
    }

    fun newId(): String = newId(System.currentTimeMillis().toULong(), bytes())

    /** Same serial worker as create: cancellation of withContext may lose its return AFTER
     * SQLite committed. Inspect durable ops, including tombstoned posts, never just live Model. */
    suspend fun journalPostCommitted(accountId: String, postId: String): Boolean =
        withContext(dispatcher) { store.hasCreatedPost(accountId, postId) }

    private fun bytes(): ByteArray = ByteArray(10).also { random.nextBytes(it) }

    private fun deviceNow(): String = JSONObject()
        .put("now", System.currentTimeMillis())
        .put("tz_offset_min", TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000)
        .put("mono", SystemClock.elapsedRealtime())
        .put("boot_id", bootId())
        .toString()

    private fun bootId(): String =
        runCatching { Settings.Global.getInt(ctx.contentResolver, Settings.Global.BOOT_COUNT).toString() }.getOrDefault("")

    /**
     * Create a local op; it is shown at once and synced when connected. Returns the op id.
     * Throws [uniffi.chorus_ffi.CoreException] when the core rejects it.
     */
    suspend fun create(kind: String, entityId: String?, payload: JSONObject, scope: String = accountScope, userTime: Long? = null,
        memberId: String? = null, expectedEditorOwner: String? = null): String =
        withContext(dispatcher) {
            check(expectedEditorOwner == null || editorOwner == expectedEditorOwner) { "The account changed. Reopen this draft in its account." }
            check(_status.value != Status.StorageError) { "The local replica could not be saved." }
            val r = replica ?: error("not set up")
            // a delete names who is here, for Trash's "Deleted by" (F9.5)
            val acting = memberId ?: Trash.actingMember(kind, _model.value)
            val n = JSONObject().put("kind", kind).put("scope", scope).put("entity_id", entityId ?: JSONObject.NULL)
                .put("payload", payload).put("member_id", acting ?: JSONObject.NULL).put("user_time", userTime ?: JSONObject.NULL)
            val out = JSONObject(r.create(n.toString(), deviceNow(), bytes()))
            try {
                store.save(r.takeChanges())
            } catch (e: Exception) {
                storageFailed(e)
                throw e
            }
            rebuild()
            refreshHeld(r)
            sendAll(out.getJSONArray("frames"))
            syncReady.value = false
            SyncWork.enqueue(ctx)
            if (kind == "post.create" && (payload.optJSONArray("attachments")?.length() ?: 0) > 0) UploadWork.enqueue(ctx)
            out.getJSONObject("op").getString("id")
        }

    /** Import a `pk;export` file (D-005), safe to repeat → how many new items, and PluralKit
     * member id → our member id so avatars can follow. */
    suspend fun importPluralkit(exportJson: String): Pair<Int, Map<String, String>> =
        withContext(dispatcher) {
            check(_status.value != Status.StorageError) { "The local replica could not be saved." }
            val r = replica ?: error("not set up")
            val out = JSONObject(r.importPluralkit(exportJson, accountScope, deviceNow()))
            try {
                store.save(r.takeChanges())
            } catch (e: Exception) {
                storageFailed(e)
                throw e
            }
            rebuild()
            refreshHeld(r)
            sendAll(out.getJSONArray("frames"))
            syncReady.value = false
            SyncWork.enqueue(ctx)
            val rows = JSONObject(r.projection()).optJSONObject("rows")?.optJSONObject("member") ?: JSONObject()
            out.getInt("added") to rows.keys().asSequence().mapNotNull { id ->
                rows.optJSONObject(id)?.optJSONObject("fields")?.optString("pk_id")?.takeIf { it.isNotEmpty() }?.let { it to id }
            }.toMap()
        }

    /** Plan against the live replica and apply on its serial worker, without a sync gap. */
    suspend fun saveMemberProfile(id: String, existed: Boolean, patch: JSONObject, fields: JSONArray, values: List<String>, expectedEditorOwner: String? = null) =
        withContext(dispatcher) {
            check(expectedEditorOwner == null || editorOwner == expectedEditorOwner) { "The account changed. Reopen this draft in its account." }
            val account = device?.accountId ?: error("not set up")
            val plan = MemberProfileSave.plan(id, existed, patch, fields, values, awaitModel(), account)
            for (op in plan) create(op.kind, op.entityId, op.payload)
        }

    // ─── persistence + model ─────────────────────────────────────────────────

    private fun changed(r: CoreReplica) {
        try { store.save(r.takeChanges()) } catch (e: Exception) { storageFailed(e); return }
        refreshIssues(r)
        refreshHeld(r)
        rebuild()
    }

    private fun refreshIssues(r: CoreReplica) {
        val rows = JSONArray(r.syncIssues())
        _syncIssues.value = (0 until rows.length()).map { n ->
            val row = rows.getJSONObject(n)
            val payload = row.optJSONObject("payload") ?: JSONObject()
            fun opt(key: String) = row.optString(key).takeIf { !row.isNull(key) && it.isNotEmpty() }
            SyncIssue(row.getString("id"), row.getString("kind"), row.getLong("at"),
                row.getString("code"), row.getString("message"), payload.optString("text"),
                row.optString("scope"), opt("entity_id"), opt("member_id"), payload)
        }
    }

    private fun refreshHeld(r: CoreReplica) {
        val held = HeldMessages.parse(r.held())
        _heldMessages.value = held.associateBy { it.messageId }
        heldTimer?.cancel()
        heldTimer = null
        val next = held.firstOrNull() ?: return
        heldTimer = scope.launch {
            delay((next.until - System.currentTimeMillis()).coerceAtLeast(0L) + 50L)
            heldTimer = null
            if (_status.value == Status.Live) {
                val frames = JSONArray(r.tick(System.currentTimeMillis()))
                changed(r) // keep the new queue state on disk before sending
                if (_status.value != Status.StorageError) sendAll(frames)
                syncReady.value = false
            } else {
                // A background socket lease may have ended; WorkManager reconnects when due.
                SyncWork.enqueue(ctx)
            }
        }
    }

    /** Call on draft changes; stop on send, clear, channel/speaker/audience change and disposal.
     * audience is "system" for system-only or chosen-member drafts. Offline frames are dropped. */
    fun sendTyping(channelId: String, member: String?, audience: String, start: Boolean) {
        require(audience == "all" || audience == "system")
        val socket = ws ?: return
        if (_status.value != Status.Live) return
        scope.launch {
            if (ws !== socket || _status.value != Status.Live || !typingThrottle.allow(channelId, start, SystemClock.elapsedRealtime())) return@launch
            socket.send(JSONObject().put("t", "typing").put("channel", channelId)
                .put("member", member ?: JSONObject.NULL).put("audience", audience)
                .put("state", if (start) "start" else "stop").toString())
        }
    }

    private fun clearTyping() {
        typingExpiry?.cancel()
        typingExpiry = null
        typingThrottle.clear()
        _typing.value = emptyMap()
    }

    private fun receiveTyping(frame: JSONObject) {
        val entry = typingEntry(frame) ?: return
        val channel = frame.optString("channel").takeIf { it.isNotEmpty() } ?: return
        _typing.value = TypingExpiry.receive(_typing.value, channel, entry,
            frame.optString("state") == "start", System.currentTimeMillis() + typingOffset)
        typingExpiry?.cancel()
        typingExpiry = scope.launch {
            while (true) {
                val next = _typing.value.values.flatten().minOfOrNull { it.until } ?: break
                delay((next - System.currentTimeMillis() - typingOffset).coerceAtLeast(1))
                _typing.value = TypingExpiry.expire(_typing.value, System.currentTimeMillis() + typingOffset)
            }
        }
    }

    /** Name lookups use the same authorized directory/author-card reads as chat, never a typing member ID. */
    private fun refreshTypingNames() {
        val dev = device ?: return
        scope.launch {
            val names = withContext(Dispatchers.IO) {
                runCatching {
                    val spaces = Spaces.list(dev)
                    val accounts = spaces.values.flatMap { it.accounts }.associate { it.id to it.shownName }
                    val authors = spaces.keys.flatMap { space ->
                        runCatching { Spaces.authorCards(dev, space).values.toList() }.getOrDefault(emptyList())
                    }.associateBy { it.id }
                    TypingNames(dev.accountId, accounts, authors)
                }.getOrNull()
            } ?: return@launch
            if (device?.deviceId != dev.deviceId) return@launch
            typingNames = names
            rebuild()
        }
    }

    /** Cancel a locally held send while the server has not accepted it. */
    suspend fun cancelHeld(messageId: String): Boolean = withContext(dispatcher) {
        val held = _heldMessages.value[messageId] ?: return@withContext false
        val r = replica ?: return@withContext false
        if (!r.cancelHeld(held.opId)) return@withContext false
        changed(r)
        setSyncReady(null, caughtScopes.containsAll(expectedScopes) &&
            HeldMessages.caughtUp(r.pendingCount(), _heldMessages.value.size))
        true
    }

    /** Dismiss only after core and the encrypted replica both forget the refused op. */
    suspend fun dismissIssue(id: String) = withContext(dispatcher) {
        val r = replica ?: return@withContext
        if (!r.dismissIssue(id)) return@withContext
        try { store.save(r.takeChanges()) } catch (e: Exception) { storageFailed(e); throw e }
        refreshIssues(r)
        // a retry refused again and then discarded was never fixed
        if (_fixedIssues.value.any { it.opId == id }) {
            _fixedIssues.value = _fixedIssues.value.filter { it.opId != id }
            store.put(SyncIssues.KEY, SyncIssues.encodeFixed(_fixedIssues.value))
        }
    }

    /**
     * Send a refused op again under a new id (F10.4 Retry): written first, then the refused one is
     * let go, so nothing is lost if either step fails. It joins the Fixed list as [title].
     */
    suspend fun retryIssue(issue: SyncIssue, title: String) {
        val op = create(issue.kind, issue.entityId, issue.payload, scope = issue.scope.ifEmpty { accountScope }, memberId = issue.memberId)
        withContext(dispatcher) {
            val now = System.currentTimeMillis()
            val fixed = SyncIssues.withFixed(SyncIssues.parseFixed(store.get(SyncIssues.KEY)), SyncIssues.Fixed(title, op, now), now)
            store.put(SyncIssues.KEY, SyncIssues.encodeFixed(fixed))
            _fixedIssues.value = fixed
        }
        dismissIssue(issue.id)
    }

    /** `confirmed`, `pending`, `refused` or `unknown` (F10.4's Fixed list). */
    suspend fun opStatus(id: String): String = withContext(dispatcher) { replica?.opStatus(id) ?: "unknown" }

    /** Core's locally replicated versions, including the original and each accepted edit. */
    suspend fun revisions(entityId: String): List<MessageRevision> = withContext(dispatcher) {
        val rows = JSONArray(replica?.revisions(entityId) ?: "[]")
        (0 until rows.length()).map { n ->
            val row = rows.getJSONObject(n)
            val fields = row.getJSONObject("fields")
            MessageRevision(row.getInt("rev"), row.getLong("at"), row.getBoolean("original"),
                fields.optString("text"),
                if (fields.has("cw") && !fields.isNull("cw")) fields.optString("cw").ifEmpty { null } else null)
        }
    }

    /** Explicit offline history read, coalesced by the front dependency revision. */
    suspend fun frontHistory(frontId: String? = null): NamedHistory? = withContext(dispatcher) {
        val r = replica ?: return@withContext null
        val account = device?.accountId ?: return@withContext null
        val front = frontId ?: account
        if (_model.value.namedFronts?.fronts?.containsKey(front) != true) return@withContext null
        frontHistories.read(account, _frontHistoryRevision.value, front,
            { r.frontSystemHistory(account) }, { r.effectiveFrontHistory(account, it) })
    }

    /** Coalesced: at most one pending rebuild of the typed model. */
    private fun rebuild() {
        if (!rebuildPending.compareAndSet(false, true)) return
        scope.launch {
            rebuildPending.set(false)
            // a signed-out phone shows nothing of what it keeps (D-153), widgets included
            if (signedOut) {
                if (_model.value != Model.Empty) { _model.value = Model.Empty; onModel?.invoke(Model.Empty) }
                return@launch
            }
            val r = replica ?: return@launch
            val dev = device ?: return@launch
            // only what changed crosses the FFI; the first time, the whole projection
            val d = JSONObject(r.projectionDelta())
            val cached = projectionCache
            val p = if (cached == null || d.optBoolean("full")) {
                JSONObject(r.projection()).also { r.projectionDelta() }
            } else {
                cached.also { Model.applyDelta(it, d) }
            }
            projectionCache = p
            val next = Model.parse(p, dev.accountId, typingNames)
            _model.value = next
            if (cached == null || d.optBoolean("full") ||
                d.optJSONObject("front_systems")?.has(dev.accountId) == true ||
                d.optJSONObject("fronts")?.has(dev.accountId) == true) {
                _frontHistoryRevision.value += 1
            }
            onModel?.invoke(next)
        }
    }

    /** The projection as last applied (store thread only). */
    private var projectionCache: JSONObject? = null

    suspend fun searchCards(ids: Set<String>): Map<String, ChatMessage> = withContext(dispatcher) {
        projectionCache?.let { Model.messageCards(it, ids) }.orEmpty()
    }

    suspend fun searchWindow(channel:String, target:String, view:SearchVisibility):List<ChatMessage> = withContext(dispatcher) {
        projectionCache?.let {Model.channelWindow(it,channel,100,target,view::message).messages}.orEmpty()
    }

    /** Older Stage rows come from the same local projection as Chat, on the store thread. */
    suspend fun stageWindow(channelId: String, limit: Int, spaceKind: String, front: List<Entry>,
        viewingAs: String?): ChannelWindow = withContext(dispatcher) {
        val p = projectionCache ?: replica?.projection()?.let(::JSONObject)
            ?: return@withContext ChannelWindow(emptyList(), false)
        val accountId = device?.accountId.orEmpty()
        Model.channelWindow(p, channelId, limit) { message ->
            memberVisible(message, spaceKind, front, viewingAs) && accountVisible(message, spaceKind, accountId)
        }
    }

    /** Called on the store thread after each model rebuild (the widget refreshes itself here). */
    @Volatile var onModel: ((Model) -> Unit)? = null

    /** A small private setting in the encrypted store (push keys and similar). */
    suspend fun setting(key: String): String? = withContext(dispatcher) { store.get(key) }

    suspend fun putSetting(key: String, value: String) = withContext(dispatcher) { store.put(key, value) }

    /** Device-only preference: Android is installed, so file retention starts enabled. */
    suspend fun setKeepEverything(on: Boolean) = withContext(dispatcher) {
        store.put("setting:keep_everything", on.toString())
        _keepEverything.value = on
        if (on && _status.value == Status.Live) scheduleFileFill()
    }

    private fun scheduleFileFill() {
        if (fillStarted || !_keepEverything.value) return
        scope.launch {
            delay(5_000)
            if (fillStarted || !_keepEverything.value || _status.value != Status.Live) return@launch
            fillStarted = true
            runCatching { fillFiles() }.onFailure { Log.w(TAG, "offline file fill failed", it) }
        }
    }

    /** A snapshot of the projection's file catalogue, then at most three IO downloads. */
    suspend fun fillFiles(): OfflineFiles.Progress = fillMutex.withLock {
        val (dev, hashes) = withContext(dispatcher) {
            val d = device ?: error("Not signed in.")
            d to OfflineFiles.filesOf(projectionCache ?: JSONObject(replica?.projection() ?: "{}"))
        }
        OfflineFiles.fill(ctx, dev, hashes) { _fileProgress.value = it }
    }

    /** Re-pull every scope and wait for digest repair before filling files. */
    suspend fun recheckAll() {
        val done = CompletableDeferred<Unit>()
        val watch = withContext(dispatcher) {
            check(_status.value == Status.Live && ws != null) { "Not connected to the server right now." }
            check(recheckWatch == null) { "A full sync is already running." }
            val r = replica ?: error("Not set up.")
            val frames = JSONArray(r.recheck())
            val waiting = mutableSetOf<String>()
            for (i in 0 until frames.length()) waiting.add(frames.getJSONObject(i).getString("scope"))
            val total = waiting.size
            val callback: (String) -> Unit = { name ->
                waiting.remove(name)
                val repairing = JSONArray(r.repairing()).length()
                _recheckProgress.value = RecheckProgress((total - waiting.size - repairing).coerceAtLeast(0), total)
                if (waiting.isEmpty() && repairing == 0) done.complete(Unit)
            }
            recheckWatch = callback
            recheckDone = done
            callback("")
            sendAll(frames)
            callback
        }
        try {
            withTimeout(120_000) { done.await() }
            if (_keepEverything.value) fillFiles()
        } finally {
            withContext(dispatcher) {
                if (recheckWatch === watch) { recheckWatch = null; recheckDone = null }
            }
        }
    }

    /** F9.6's usage bar: the replica, downloaded files, the rest. */
    suspend fun spaceUsage(): DeviceSpace.Usage = withContext(Dispatchers.IO) {
        DeviceSpace.measure(ctx.filesDir, ctx.cacheDir, ctx.getDatabasePath("unused").parentFile!!)
    }

    /**
     * Clear downloaded files (F9.6), keeping every message and anything not yet uploaded. Keep
     * everything turns off with it, or the next fill would fetch them all again.
     */
    suspend fun clearDownloaded(): Long = fillMutex.withLock {
        if (_keepEverything.value) setKeepEverything(false)
        withContext(Dispatchers.IO) { DeviceSpace.clearDownloaded(ctx.filesDir, ctx.cacheDir) }
    }

    /** Replica, retained files, and evictable cache, including SQLite's WAL. */
    suspend fun spaceUsedBytes(): Long = withContext(Dispatchers.IO) {
        fun size(root: File): Long = if (!root.exists()) 0 else root.walkTopDown()
            .filter { it.isFile }.sumOf { it.length() }
        size(ctx.filesDir) + size(ctx.cacheDir) + size(ctx.getDatabasePath("unused").parentFile!!)
    }

    /** The signed-in device, after the replica has loaded. */
    suspend fun awaitDevice(): DeviceRecord? = withContext(dispatcher) { device }

    /** A signed-in REST call, renewing an expired or rejected session once. */
    suspend fun authedCall(method: String, path: String, body: JSONObject? = null): JSONObject {
        val dev = withContext(dispatcher) {
            checkSignedIn()
            var d = device ?: throw ApiException("Not signed in.", "unauthenticated")
            if (d.expiresAt <= System.currentTimeMillis() + 60_000 && renewSession() == SyncLink.Renewal.Renewed) {
                d = device ?: throw ApiException("Not signed in.", "unauthenticated")
            }
            checkSignedIn()
            d
        }
        return try {
            Api.call(method, dev.base, path, body, dev.session)
        } catch (e: ApiException) {
            if (e.code != "unauthenticated") throw e
            val fresh = withContext(dispatcher) {
                if (renewSession() != SyncLink.Renewal.Renewed) { checkSignedIn(); throw e }
                device ?: throw e
            }
            Api.call(method, fresh.base, path, body, fresh.session)
        }
    }

    /** A signed-out phone (D-148) asks the server nothing more, and says why. */
    private fun checkSignedIn() {
        if (signedOut) throw ApiException(SIGNED_OUT_MESSAGE, "signed_out")
    }

    /** This account's devices, this one first (F1.6). */
    suspend fun linkedDevices(): List<LinkedDevice> = DeviceLinks.devices(authedCall("GET", "/me"), device?.deviceId)

    /** Sign out another device of this account (`POST /devices/{id}/revoke`); with [wipe] its copy is
     * erased the next time it connects instead of two days later (D-152). */
    suspend fun revokeDevice(id: String, wipe: Boolean = false) {
        authedCall("POST", "/devices/$id/revoke", DeviceLinks.revokeBody(wipe))
    }

    /** Create a one-use invite for another device, renewing the signed session if needed. */
    suspend fun deviceInvite(): DeviceInvite = withContext(dispatcher) {
        checkSignedIn()
        var dev = device ?: error("not set up")
        if (dev.expiresAt <= System.currentTimeMillis() + 60_000) {
            val renewed = renewSession()
            checkSignedIn()
            check(renewed == SyncLink.Renewal.Renewed) { "Could not renew the device session." }
            dev = device ?: error("not set up")
        }
        try {
            DeviceLinks.invite(Api.post(dev.base, "/devices/invite", JSONObject(), dev.session))
        } catch (e: ApiException) {
            if (e.code != "unauthenticated") throw e
            if (renewSession() != SyncLink.Renewal.Renewed) { checkSignedIn(); throw e }
            dev = device ?: error("not set up")
            DeviceLinks.invite(Api.post(dev.base, "/devices/invite", JSONObject(), dev.session))
        }
    }

    /** A fresh model, waiting for the replica to load (for the widget in a cold process). */
    suspend fun awaitModel(): Model = withContext(dispatcher) {
        if (signedOut) return@withContext Model.Empty
        val r = replica ?: return@withContext Model.Empty
        val dev = device ?: return@withContext Model.Empty
        Model.parse(JSONObject(r.projection()), dev.accountId, typingNames)
    }

    private fun storageFailed(e: Exception) {
        clearTyping()
        Log.e(TAG, "local replica unavailable", e)
        syncReady.value = false
        heldTimer?.cancel()
        heldTimer = null
        transition(SyncLink.Event.StorageFailed)
    }

    /** Own a socket only while visible or while a bounded sync work is running. */
    fun setForeground(active: Boolean) {
        main.post {
            foreground = active
            transition(SyncLink.Event.Wanted(foreground || workLeases > 0))
        }
    }

    /** WorkManager waits for catch-up and the outbox to drain, then releases its socket lease. */
    suspend fun syncOnce(): Boolean {
        withContext(Dispatchers.Main) {
            workLeases++
            transition(SyncLink.Event.Wanted(true))
        }
        return try {
            // nothing to wait for without a device or once signed out (D-148): done, not retried
            val result = withTimeoutOrNull(25_000) {
                status.combine(syncReady) { state, synced -> state to synced }
                    .first { (state, synced) -> state == Status.NoDevice || state == Status.SignedOut || state == Status.StorageError || synced }
            }
            result?.let { (state, synced) -> state == Status.NoDevice || state == Status.SignedOut || state != Status.StorageError && synced } ?: false
        } finally {
            withContext(NonCancellable + Dispatchers.Main) {
                workLeases--
                transition(SyncLink.Event.Wanted(foreground || workLeases > 0))
            }
        }
    }

    /**
     * The one way the connection's lifecycle changes (SyncLink): reduce, carry out the effects and
     * publish, all under [linkLock], so a frame handled on the store thread and a close on the main
     * thread can't interleave into "Live with no socket". Effects only post or enqueue work.
     */
    private fun transition(event: SyncLink.Event): SyncLink.State = synchronized(linkLock) {
        val step = SyncLink.reduce(link, event)
        link = step.state
        for (effect in step.effects) perform(effect)
        if (link.socket == null) ws = null
        if (link.status != Status.Live) syncReady.value = false
        _status.value = link.status
        _connectFailures.value = link.failures
        link
    }

    /** Whether socket [n] is still the one we own. */
    private fun current(n: Long): Boolean = synchronized(linkLock) { link.socket == n }

    /** Caught up, said only while socket [n] is live and still ours. */
    private fun setSyncReady(n: Long?, ready: Boolean) = synchronized(linkLock) {
        syncReady.value = ready && (if (n == null) link.status == Status.Live && link.socket != null else link.liveOn(n))
    }

    private fun perform(effect: SyncLink.Effect) {
        when (effect) {
            is SyncLink.Effect.Open -> openSocket(effect.socket)
            is SyncLink.Effect.Close -> if (wsNumber == effect.socket) { ws?.close(1000, effect.reason); ws = null }
            is SyncLink.Effect.Retry -> {
                main.removeCallbacks(retry)
                main.postDelayed(retry, (effect.after * (0.7 + Math.random() * 0.6)).toLong())
            }
            SyncLink.Effect.CancelRetry -> main.removeCallbacks(retry)
            SyncLink.Effect.Renew -> scope.launch {
                val result = renewSession()
                // a refusal has already left the phone signed out
                if (result != SyncLink.Renewal.Refused) transition(SyncLink.Event.RenewalDone(result))
            }
            SyncLink.Effect.Disconnected -> scope.launch {
                recheckDone?.completeExceptionally(IllegalStateException("Connection lost during full sync."))
                clearTyping(); replica?.disconnect(); syncReady.value = false
            }
        }
    }

    // ─── socket ──────────────────────────────────────────────────────────────

    private fun wsUrl(base: String) = base.replaceFirst("http", "ws") + "/api/v1/sync"

    private fun sendAll(frames: JSONArray) {
        val s = ws ?: return
        if (_status.value != Status.Live) return
        for (i in 0 until frames.length()) s.send(frames.get(i).toString())
    }

    /** Socket [n] (under [linkLock], from [perform]); its listener reports back by number. */
    private fun openSocket(n: Long) {
        val dev = device
        val r = replica
        if (dev == null || r == null) {
            main.post { transition(SyncLink.Event.Dropped(n)) }
            return
        }
        scope.launch { expectedScopes.clear(); caughtScopes.clear() }
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                UploadWork.enqueue(ctx) // files queued while offline go up now
                scope.launch {
                    if (!current(n)) return@launch
                    val clock = JSONObject().put("wall", System.currentTimeMillis())
                        .put("mono", SystemClock.elapsedRealtime()).put("boot_id", bootId())
                    webSocket.send(r.connect(clock.toString(), device!!.session))
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = runCatching { JSONObject(text) }.getOrNull() ?: return
                // too many frames: the server closes the socket; say so once (F10.7)
                if (frame.optString("t") == "error" && frame.optString("code") == "rate_limited") Notices.slowDown(null)
                // an expired session, or this device signed out from another one: renew to tell which
                if (frame.optString("t") == "error" && frame.optString("code") == "unauthenticated") {
                    transition(SyncLink.Event.Unauthenticated(n))
                    return
                }
                scope.launch {
                    if (!current(n) || _status.value == Status.StorageError) return@launch
                    val kind = frame.optString("t")
                    if (kind == "typing") { receiveTyping(frame); return@launch }
                    val out = runCatching { JSONArray(r.onFrame(text, System.currentTimeMillis())) }
                        .onFailure { Log.w(TAG, "frame rejected by core", it) }
                        .getOrNull() ?: return@launch
                    changed(r) // durable before any dependent frame goes out
                    if (_status.value == Status.StorageError) return@launch
                    if (kind == "welcome") {
                        // a welcome from a socket closed meanwhile changes nothing
                        if (!transition(SyncLink.Event.Welcome(n)).liveOn(n)) return@launch
                        clearTyping()
                        typingOffset = frame.getLong("server_time") - System.currentTimeMillis()
                        refreshTypingNames()
                        expectedScopes.clear()
                        caughtScopes.clear()
                        frame.optJSONArray("scopes")?.let { scopes ->
                            for (i in 0 until scopes.length()) expectedScopes.add(scopes.getString(i))
                        }
                        scheduleFileFill()
                        if (frame.optBoolean("reconcile")) {
                            val hashes = JSONArray(r.restoringBlobs())
                            val localDevice = device
                            if (localDevice != null && hashes.length() > 0) {
                                val names = (0 until hashes.length()).map { hashes.getString(it) }
                                scope.launch(Dispatchers.IO) {
                                    if (Blobs.queueRestore(ctx, localDevice, names) > 0) UploadWork.enqueue(ctx)
                                }
                            }
                        }
                    }
                    if (kind == "scope") {
                        refreshTypingNames()
                        frame.optJSONArray("remove")?.let { a -> for (i in 0 until a.length()) { expectedScopes.remove(a.getString(i)); caughtScopes.remove(a.getString(i)) } }
                        frame.optJSONArray("add")?.let { a -> for (i in 0 until a.length()) { expectedScopes.add(a.getString(i)); caughtScopes.remove(a.getString(i)) } }
                    }
                    for (i in 0 until out.length()) webSocket.send(out.get(i).toString())
                    if (kind == "caught") {
                        val scopeName = frame.optString("scope")
                        val repairing = (0 until out.length()).any { i ->
                            val reply = out.getJSONObject(i)
                            reply.optString("t") == "pull" && reply.optString("scope") == scopeName
                        }
                        if (!repairing) caughtScopes.add(scopeName)
                        recheckWatch?.invoke(scopeName)
                    }
                    setSyncReady(n, caughtScopes.containsAll(expectedScopes) &&
                        HeldMessages.caughtUp(r.pendingCount(), _heldMessages.value.size))
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { transition(SyncLink.Event.Dropped(n)) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.i(TAG, "sync socket failed: ${t.message}")
                transition(SyncLink.Event.Dropped(n))
            }
        }
        wsNumber = n
        ws = Api.http.newWebSocket(Request.Builder().url(wsUrl(dev.base)).build(), listener)
    }

    /**
     * A fresh session by signing the server's challenge. `forbidden` from the challenge or the
     * session call is the server refusing this device for good (signed out from another device,
     * D-148): the phone is left [Status.SignedOut] whoever asked. Anything else (no network, 5xx,
     * rate limits, a bad signature) is [SyncLink.Renewal.Failed] and keeps the usual backoff.
     */
    private suspend fun renewSession(): SyncLink.Renewal {
        val dev = device ?: return SyncLink.Renewal.Failed
        if (signedOut) return SyncLink.Renewal.Refused
        return try {
            val next = Api.renew(dev)
            try { store.put("device", next.toJson()) } catch (e: Exception) { storageFailed(e); return SyncLink.Renewal.Failed }
            device = next
            SyncLink.Renewal.Renewed
        } catch (e: ApiException) {
            Log.w(TAG, "session renewal failed (${e.code})", e)
            val record = SignedOut.refusal(e, typingOffset) ?: return SyncLink.Renewal.Failed
            // only if this is still the device that asked (not one linked meanwhile)
            if (device?.deviceId == dev.deviceId) signOut(record)
            SyncLink.Renewal.Refused
        } catch (e: Exception) {
            Log.w(TAG, "session renewal failed", e)
            SyncLink.Renewal.Failed
        }
    }

    /**
     * The server refused this device for good (D-153; store thread): remember when and until when,
     * hide what is kept, and erase it if its time is already up, else book the erase (D-152).
     */
    private fun signOut(record: SignedOutRecord) {
        try { store.put(SIGNED_OUT, record.toJson()) } catch (e: Exception) { storageFailed(e); return }
        _signedOut.value = record
        transition(SyncLink.Event.RenewalDone(SyncLink.Renewal.Refused))
        val now = System.currentTimeMillis()
        if (record.due(now)) { erase(Erase.Reason.WipeAtPassed); start(); return }
        record.delay(now)?.let { WipeWork.schedule(ctx, it) }
        rebuild()
    }

    /** [WipeWork]: erase if the deadline has passed; else how long is left (null: nothing to do). */
    internal suspend fun eraseIfDue(): Long? = withContext(dispatcher) {
        val record = _signedOut.value ?: return@withContext null
        val now = System.currentTimeMillis()
        if (!record.due(now)) return@withContext record.delay(now)
        erase(Erase.Reason.WipeAtPassed)
        start()
        null
    }

    /**
     * Onboarding with an invite other than a device link of the kept copy's account (D-153), once
     * the person agreed: erase what this signed-out phone keeps, unsynced changes included (D-152).
     */
    suspend fun eraseKept(): Boolean = withContext(dispatcher) {
        if (!signedOut) return@withContext false
        erase(Erase.Reason.Enrolment)
        start()
        true
    }

    /**
     * The one erase (D-152; store thread): everything this install holds goes, and the app is a
     * fresh install's. Called only for a passed `wipe_at`, the enrolment rule, or to finish one
     * that was cut short; the caller starts again afterwards.
     */
    private fun erase(reason: Erase.Reason) {
        Log.i(TAG, "erasing this phone's copy: $reason")
        val pushState = runCatching { store.get(Push.STATE_KEY) }.getOrNull()
        heldTimer?.cancel()
        heldTimer = null
        clearTyping()
        runCatching { store.close() }
        replica = null
        device = null
        projectionCache = null
        val failed = Erase.run({ Erase.markPending(ctx) }, Erase.deviceSteps(ctx, pushState), { Erase.clearPending(ctx) })
        if (failed.isNotEmpty()) Log.w(TAG, "erase incomplete ($failed); it goes on at the next start")
        store = Store(ctx)
        _signedOut.value = null
        _model.value = Model.Empty
        _syncIssues.value = emptyList()
        _fixedIssues.value = emptyList()
        _lastSynced.value = null
        _heldMessages.value = emptyMap()
        _fileProgress.value = null
        _recheckProgress.value = null
        fillStarted = false
        Inbox.forget()
        onModel?.invoke(Model.Empty)
        runCatching { garden.vayne.chorus.widget.UnreadWidget.refreshAll(ctx, Model.Empty) }
    }

    /** Network came back or the app came to the front: try now instead of waiting out the backoff.
     * Nothing while signed out, renewing, unwanted or already connected. */
    fun reconnectNow() {
        transition(SyncLink.Event.ConnectNow)
    }

    companion object {
        private const val TAG = "Chorus"
        private const val LAST_SYNCED = "sync:last_synced"
        /** While this device is signed out (D-153): a [SignedOutRecord]; removed when it is linked again. */
        private const val SIGNED_OUT = "sync:signed_out"
        const val SIGNED_OUT_MESSAGE = "This phone was signed out from another device."
        @Volatile private var instance: Chorus? = null

        fun get(ctx: Context): Chorus =
            instance ?: synchronized(this) { instance ?: Chorus(ctx.applicationContext).also { instance = it } }
    }
}
