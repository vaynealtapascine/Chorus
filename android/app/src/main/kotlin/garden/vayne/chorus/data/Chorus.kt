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
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import uniffi.chorus_ffi.CoreReplica
import uniffi.chorus_ffi.newId

enum class Status { Loading, NoDevice, Offline, Connecting, Live, StorageError }

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
        val dev = store.get("device")?.let { runCatching { DeviceRecord.fromJson(it) }.getOrNull() }
        device = dev
        if (dev == null) {
            _status.value = Status.NoDevice
            return
        }
        replica = CoreReplica.restore(dev.deviceId, dev.node, store.get("meta").orEmpty(), store.opsJson(), store.get("hlc").orEmpty())
        rebuild()
        main.post { connect() }
    }

    /** After enrolment. */
    fun adopt(dev: DeviceRecord) {
        scope.launch {
            try {
                store.put("device", dev.toJson())
                start()
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
            sendAll(out.getJSONArray("frames"))
            syncReady.value = false
            SyncWork.enqueue(ctx)
            out.getJSONObject("op").getString("id")
        }

    // ─── persistence + model ─────────────────────────────────────────────────

    private fun changed(r: CoreReplica) {
        try { store.save(r.takeChanges()) } catch (e: Exception) { storageFailed(e); return }
        rebuild()
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

    /** Called on the store thread after each model rebuild (the widget refreshes itself here). */
    @Volatile var onModel: ((Model) -> Unit)? = null

    /** A small private setting in the encrypted store (push keys and similar). */
    suspend fun setting(key: String): String? = withContext(dispatcher) { store.get(key) }

    suspend fun putSetting(key: String, value: String) = withContext(dispatcher) { store.put(key, value) }

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
                    }
                    syncReady.value = _status.value == Status.Live &&
                        caughtScopes.containsAll(expectedScopes) && r.pendingCount() == 0UL
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
            scope.launch { replica?.disconnect(); syncReady.value = false }
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
