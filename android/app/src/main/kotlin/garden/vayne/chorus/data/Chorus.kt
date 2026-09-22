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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import uniffi.chorus_ffi.CoreReplica
import uniffi.chorus_ffi.newId

enum class Status { Loading, NoDevice, Offline, Connecting, Live }

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
    private val scope = CoroutineScope(SupervisorJob() + worker.asCoroutineDispatcher())
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

    val accountScope: String get() = "account:${device?.accountId.orEmpty()}"

    init {
        scope.launch { start() }
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
            store.put("device", dev.toJson())
            start()
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
    fun create(kind: String, entityId: String?, payload: JSONObject, scope: String = accountScope, userTime: Long? = null): String {
        val r = replica ?: error("not set up")
        val n = JSONObject().put("kind", kind).put("scope", scope).put("entity_id", entityId ?: JSONObject.NULL)
            .put("payload", payload).put("member_id", JSONObject.NULL).put("user_time", userTime ?: JSONObject.NULL)
        val out = JSONObject(r.create(n.toString(), deviceNow(), bytes()))
        sendAll(out.getJSONArray("frames"))
        changed()
        return out.getJSONObject("op").getString("id")
    }

    // ─── persistence + model ─────────────────────────────────────────────────

    private fun changed() {
        scope.launch {
            val r = replica ?: return@launch
            store.save(r.takeChanges())
        }
        rebuild()
    }

    /** Coalesced: at most one pending rebuild of the typed model. */
    private fun rebuild() {
        if (!rebuildPending.compareAndSet(false, true)) return
        scope.launch {
            rebuildPending.set(false)
            val r = replica ?: return@launch
            val dev = device ?: return@launch
            _model.value = Model.parse(r.projection(), dev.accountId)
        }
    }

    // ─── socket ──────────────────────────────────────────────────────────────

    private fun wsUrl(base: String) = base.replaceFirst("http", "ws") + "/api/v1/sync"

    private fun sendAll(frames: JSONArray) {
        val s = ws ?: return
        if (_status.value != Status.Live) return
        for (i in 0 until frames.length()) s.send(frames.get(i).toString())
    }

    private fun connect() {
        val dev = device ?: return
        val r = replica ?: return
        if (ws != null) return
        _status.value = Status.Connecting
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val clock = JSONObject().put("wall", System.currentTimeMillis())
                    .put("mono", SystemClock.elapsedRealtime()).put("boot_id", bootId())
                webSocket.send(r.connect(clock.toString(), device!!.session))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = runCatching { JSONObject(text) }.getOrNull() ?: return
                if (frame.optString("t") == "error" && frame.optString("code") == "unauthenticated") {
                    if (renewing) return
                    renewing = true
                    webSocket.close(1000, null)
                    scope.launch {
                        val renewed = renewSession()
                        main.post {
                            if (ws === webSocket) {
                                ws = null
                                replica?.disconnect()
                            }
                            renewing = false
                            _status.value = Status.Offline
                            if (renewed) reconnectNow() else schedule()
                        }
                    }
                    return
                }
                if (frame.optString("t") == "welcome") {
                    _status.value = Status.Live
                    backoff = 1000
                }
                val out = runCatching { JSONArray(r.onFrame(text, System.currentTimeMillis())) }
                    .onFailure { Log.w(TAG, "frame rejected by core", it) }
                    .getOrNull() ?: return
                for (i in 0 until out.length()) webSocket.send(out.get(i).toString())
                changed()
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
            replica?.disconnect()
            _status.value = Status.Offline
            if (!renewing) schedule()
        }
    }

    private suspend fun renewSession(): Boolean {
        val dev = device ?: return false
        return try {
            val next = Api.renew(dev)
            store.put("device", next.toJson())
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
        if (ws != null || device == null || renewing) return
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
