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

enum class Status { Loading, NoDevice, Offline, Connecting, Live, StorageError }

data class RecheckProgress(val checked: Int, val total: Int)

/** A permanently refused local op; core keeps the payload until the owner dismisses it. */
data class SyncIssue(val id: String, val kind: String, val at: Long, val code: String,
    val message: String, val text: String)

data class MessageRevision(val rev: Int, val at: Long, val original: Boolean, val text: String,
    val contentWarning: String?)

/**
 * The app's one sync client (CLIENTS.md §2.3): owns the core replica, the sync socket and the
 * on-disk store. All protocol logic is in chorus-core; this class only moves bytes and timers —
 * the Android twin of web/src/lib/sync/client.ts.
 *
 * Threading: the replica is internally locked; persistence and model rebuilds run on one
 * background thread, in order.
 */
class Chorus private constructor(private val ctx: Context) {
    private val store = Store(ctx)
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "chorus-store") }
    private val dispatcher = worker.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val main = Handler(Looper.getMainLooper())
    private val random = SecureRandom()

    private val _status = MutableStateFlow(Status.Loading)
    val status: StateFlow<Status> = _status
    private val _model = MutableStateFlow(Model.Empty)
    val model: StateFlow<Model> = _model
    private val _keepEverything = MutableStateFlow(true)
    val keepEverything: StateFlow<Boolean> = _keepEverything
    private val _fileProgress = MutableStateFlow<OfflineFiles.Progress?>(null)
    val fileProgress: StateFlow<OfflineFiles.Progress?> = _fileProgress
    private val _syncIssues = MutableStateFlow<List<SyncIssue>>(emptyList())
    val syncIssues: StateFlow<List<SyncIssue>> = _syncIssues
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
    @Volatile private var replica: CoreReplica? = null
    @Volatile private var ws: WebSocket? = null
    @Volatile private var renewing = false
    private var backoff = 1000L
    private val rebuildPending = AtomicBoolean(false)
    private val syncReady = MutableStateFlow(false)
    private val expectedScopes = mutableSetOf<String>() // store thread only
    private val caughtScopes = mutableSetOf<String>() // store thread only
    private var foreground = false // main thread only
    private var workLeases = 0 // main thread only

    val accountScope: String get() = "account:${device?.accountId.orEmpty()}"

    init {
        scope.launch {
            try { start() } catch (e: Exception) { storageFailed(e) }
        }
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        cm?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { main.post { reconnectNow() } }
        })
    }

    private fun start() {
        heldTimer?.cancel()
        heldTimer = null
        _heldMessages.value = emptyMap()
        _keepEverything.value = store.get("setting:keep_everything") != "false"
        val dev = store.get("device")?.let { runCatching { DeviceRecord.fromJson(it) }.getOrNull() }
        device = dev
        Pins.trust(dev?.pin)
        if (dev == null) {
            _status.value = Status.NoDevice
            return
        }
        replica = CoreReplica.restore(dev.deviceId, dev.node, store.get("meta").orEmpty(), store.opsJson(), store.get("hlc").orEmpty())
        refreshIssues(replica!!)
        refreshHeld(replica!!)
        rebuild()
        main.post { connect() }
    }

    /** After enrolment. */
    fun adopt(dev: DeviceRecord) {
        scope.launch {
            try {
                store.put("device", dev.toJson())
                start()
                // app start registered nothing while signed out: do it now, not at the next launch
                Push.ensure(ctx)
            } catch (e: Exception) { storageFailed(e) }
        }
    }

    fun newId(): String = newId(System.currentTimeMillis().toULong(), bytes())

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
    suspend fun create(kind: String, entityId: String?, payload: JSONObject, scope: String = accountScope, userTime: Long? = null): String =
        withContext(dispatcher) {
            check(_status.value != Status.StorageError) { "The local replica could not be saved." }
            val r = replica ?: error("not set up")
            val n = JSONObject().put("kind", kind).put("scope", scope).put("entity_id", entityId ?: JSONObject.NULL)
                .put("payload", payload).put("member_id", JSONObject.NULL).put("user_time", userTime ?: JSONObject.NULL)
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
            out.getJSONObject("op").getString("id")
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
            SyncIssue(row.getString("id"), row.getString("kind"), row.getLong("at"),
                row.getString("code"), row.getString("message"),
                row.optJSONObject("payload")?.optString("text").orEmpty())
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

    /** Cancel a locally held send while the server has not accepted it. */
    suspend fun cancelHeld(messageId: String): Boolean = withContext(dispatcher) {
        val held = _heldMessages.value[messageId] ?: return@withContext false
        val r = replica ?: return@withContext false
        if (!r.cancelHeld(held.opId)) return@withContext false
        changed(r)
        syncReady.value = _status.value == Status.Live && caughtScopes.containsAll(expectedScopes) &&
            HeldMessages.caughtUp(r.pendingCount(), _heldMessages.value.size)
        true
    }

    /** Dismiss only after core and the encrypted replica both forget the refused op. */
    suspend fun dismissIssue(id: String) = withContext(dispatcher) {
        val r = replica ?: return@withContext
        if (!r.dismissIssue(id)) return@withContext
        try { store.save(r.takeChanges()) } catch (e: Exception) { storageFailed(e); throw e }
        refreshIssues(r)
    }

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

    /** Coalesced: at most one pending rebuild of the typed model. */
    private fun rebuild() {
        if (!rebuildPending.compareAndSet(false, true)) return
        scope.launch {
            rebuildPending.set(false)
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
            val next = Model.parse(p, dev.accountId)
            _model.value = next
            onModel?.invoke(next)
        }
    }

    /** The projection as last applied (store thread only). */
    private var projectionCache: JSONObject? = null

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

    /** Replica, retained files, and evictable cache, including SQLite's WAL. */
    suspend fun spaceUsedBytes(): Long = withContext(Dispatchers.IO) {
        fun size(root: File): Long = if (!root.exists()) 0 else root.walkTopDown()
            .filter { it.isFile }.sumOf { it.length() }
        size(ctx.filesDir) + size(ctx.cacheDir) + size(ctx.getDatabasePath("unused").parentFile!!)
    }

    /** The signed-in device, after the replica has loaded. */
    suspend fun awaitDevice(): DeviceRecord? = withContext(dispatcher) { device }

    /** Create a one-use invite for another device, renewing the signed session if needed. */
    suspend fun deviceInvite(): String = withContext(dispatcher) {
        var dev = device ?: error("not set up")
        if (dev.expiresAt <= System.currentTimeMillis() + 60_000) {
            check(renewSession()) { "Could not renew the device session." }
            dev = device ?: error("not set up")
        }
        try {
            Api.post(dev.base, "/devices/invite", JSONObject(), dev.session).getString("url")
        } catch (e: ApiException) {
            if (e.code != "unauthenticated" || !renewSession()) throw e
            dev = device ?: error("not set up")
            Api.post(dev.base, "/devices/invite", JSONObject(), dev.session).getString("url")
        }
    }

    /** A fresh model, waiting for the replica to load (for the widget in a cold process). */
    suspend fun awaitModel(): Model = withContext(dispatcher) {
        val r = replica ?: return@withContext Model.Empty
        val dev = device ?: return@withContext Model.Empty
        Model.parse(r.projection(), dev.accountId)
    }

    private fun storageFailed(e: Exception) {
        Log.e(TAG, "local replica unavailable", e)
        syncReady.value = false
        heldTimer?.cancel()
        heldTimer = null
        _status.value = Status.StorageError
        main.post { ws?.close(1000, "storage unavailable"); ws = null; main.removeCallbacks(retry) }
    }

    /** Own a socket only while visible or while a bounded sync work is running. */
    fun setForeground(active: Boolean) {
        main.post {
            foreground = active
            if (active) reconnectNow() else if (workLeases == 0) closeSocket()
        }
    }

    /** WorkManager waits for catch-up and the outbox to drain, then releases its socket lease. */
    suspend fun syncOnce(): Boolean {
        withContext(Dispatchers.Main) {
            workLeases++
            reconnectNow()
        }
        return try {
            val result = withTimeoutOrNull(25_000) {
                status.combine(syncReady) { state, synced -> state to synced }
                    .first { (state, synced) -> state == Status.NoDevice || state == Status.StorageError || synced }
            }
            result?.let { (state, synced) -> state == Status.NoDevice || state != Status.StorageError && synced } ?: false
        } finally {
            withContext(NonCancellable + Dispatchers.Main) {
                workLeases--
                if (!foreground && workLeases == 0) closeSocket()
            }
        }
    }

    private fun closeSocket() {
        main.removeCallbacks(retry)
        val socket = ws ?: return
        ws = null
        socket.close(1000, "background")
        scope.launch { replica?.disconnect(); syncReady.value = false }
        _status.value = Status.Offline
    }

    // ─── socket ──────────────────────────────────────────────────────────────

    private fun wsUrl(base: String) = base.replaceFirst("http", "ws") + "/api/v1/sync"

    private fun sendAll(frames: JSONArray) {
        val s = ws ?: return
        if (_status.value != Status.Live) return
        for (i in 0 until frames.length()) s.send(frames.get(i).toString())
    }

    private fun connect() {
        if (!foreground && workLeases == 0 || _status.value == Status.StorageError) return
        val dev = device ?: return
        val r = replica ?: return
        if (ws != null) return
        _status.value = Status.Connecting
        syncReady.value = false
        scope.launch { expectedScopes.clear(); caughtScopes.clear() }
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                UploadWork.enqueue(ctx) // files queued while offline go up now
                scope.launch {
                    if (ws !== webSocket) return@launch
                    val clock = JSONObject().put("wall", System.currentTimeMillis())
                        .put("mono", SystemClock.elapsedRealtime()).put("boot_id", bootId())
                    webSocket.send(r.connect(clock.toString(), device!!.session))
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = runCatching { JSONObject(text) }.getOrNull() ?: return
                if (frame.optString("t") == "error" && frame.optString("code") == "unauthenticated") {
                    if (renewing) return
                    renewing = true
                    webSocket.close(1000, null)
                    scope.launch {
                        val renewed = renewSession()
                        r.disconnect()
                        main.post {
                            if (ws === webSocket) {
                                ws = null
                            }
                            renewing = false
                            if (_status.value != Status.StorageError) {
                                _status.value = Status.Offline
                                if (renewed) reconnectNow()
                                else if (foreground || workLeases > 0) schedule()
                            }
                        }
                    }
                    return
                }
                scope.launch {
                    if (ws !== webSocket || _status.value == Status.StorageError) return@launch
                    val kind = frame.optString("t")
                    val out = runCatching { JSONArray(r.onFrame(text, System.currentTimeMillis())) }
                        .onFailure { Log.w(TAG, "frame rejected by core", it) }
                        .getOrNull() ?: return@launch
                    changed(r) // durable before any dependent frame goes out
                    if (_status.value == Status.StorageError) return@launch
                    if (kind == "welcome") {
                        expectedScopes.clear()
                        caughtScopes.clear()
                        frame.optJSONArray("scopes")?.let { scopes ->
                            for (i in 0 until scopes.length()) expectedScopes.add(scopes.getString(i))
                        }
                        _status.value = Status.Live
                        backoff = 1000
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
                    syncReady.value = _status.value == Status.Live &&
                        caughtScopes.containsAll(expectedScopes) &&
                        HeldMessages.caughtUp(r.pendingCount(), _heldMessages.value.size)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dropped(webSocket)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.i(TAG, "sync socket failed: ${t.message}")
                dropped(webSocket)
            }
        }
        ws = Api.http.newWebSocket(Request.Builder().url(wsUrl(dev.base)).build(), listener)
    }

    private fun dropped(socket: WebSocket) {
        main.post {
            if (ws !== socket) return@post
            ws = null
            scope.launch {
                recheckDone?.completeExceptionally(IllegalStateException("Connection lost during full sync."))
                replica?.disconnect(); syncReady.value = false
            }
            _status.value = Status.Offline
            if (!renewing && (foreground || workLeases > 0)) schedule()
        }
    }

    private suspend fun renewSession(): Boolean {
        val dev = device ?: return false
        return try {
            val next = Api.renew(dev)
            try { store.put("device", next.toJson()) } catch (e: Exception) { storageFailed(e); return false }
            device = next
            true
        } catch (e: Exception) {
            Log.w(TAG, "session renewal failed", e)
            false
        }
    }

    private val retry = Runnable { connect() }

    private fun schedule() {
        main.removeCallbacks(retry)
        val jitter = (backoff * (0.7 + Math.random() * 0.6)).toLong()
        main.postDelayed(retry, jitter)
        backoff = minOf(backoff * 2, 5 * 60_000L)
    }

    /** Network came back or the app came to the front: try now instead of waiting out the backoff. */
    fun reconnectNow() {
        if (ws != null || device == null || renewing || !foreground && workLeases == 0 || _status.value == Status.StorageError) return
        backoff = 1000
        main.removeCallbacks(retry)
        connect()
    }

    companion object {
        private const val TAG = "Chorus"
        @Volatile private var instance: Chorus? = null

        fun get(ctx: Context): Chorus =
            instance ?: synchronized(this) { instance ?: Chorus(ctx.applicationContext).also { instance = it } }
    }
}
