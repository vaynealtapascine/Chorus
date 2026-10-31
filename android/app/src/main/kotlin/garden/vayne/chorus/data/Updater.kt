package garden.vayne.chorus.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * In-app updates over the tailnet (CLIENTS.md §5a, D-051): the server publishes the APK and its
 * sha256; the app downloads it, verifies it, and installs through a PackageInstaller session.
 *
 * Opening the app checks (at most every few minutes), fetches a newer build on unmetered networks
 * and offers it in a banner ([state]); leaving the app installs it when Android allows that without
 * a prompt (Chorus is the installer of record, Android 12+). While the app is open, a confirmation
 * Android asks for is shown at once; in the background it becomes a one-tap notification. A
 * 12-hour background check on unmetered networks covers phones that stay closed.
 */
object Updater {
    private const val TAG = "ChorusUpdate"
    private const val CHANNEL = "updates"
    private const val OPEN_CHECK_MS = 5 * 60_000L

    data class Release(val versionCode: Long, val versionName: String, val sha256: String, val size: Long, val url: String)

    /** What the update banner shows. */
    sealed interface State {
        data object Idle : State
        /** Newer build published, not fetched yet (a metered network): fetch on request. */
        data class Available(val release: Release) : State
        data class Downloading(val release: Release, val progress: Float) : State
        data class Ready(val release: Release, val apk: File) : State
        /** "Install unknown apps" is off for Chorus: the banner opens that switch. */
        data class NeedsPermission(val release: Release, val apk: File) : State
        data class Installing(val release: Release) : State
        data class Failed(val release: Release?, val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state
    @Volatile var foreground = false
        private set
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    @Volatile private var lastCheck = 0L

    fun installedVersion(ctx: Context): Long {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    }

    suspend fun latest(ctx: Context): Release? {
        val dev = Chorus.get(ctx).awaitDevice() ?: return null
        val j = runCatching { Api.call("GET", dev.base, "/android/latest", null) }
            .onFailure { Log.w(TAG, "update check failed", it) }.getOrNull() ?: return null
        return Release(
            j.optLong("version_code"), j.optString("version_name"), j.optString("sha256"), j.optLong("size"),
            j.optString("url").ifEmpty { "${dev.base}/download/android" },
        )
    }

    /** The app came to the front: look for a newer build, or carry on with one already found. */
    fun onOpen(ctx: Context) {
        foreground = true
        val app = ctx.applicationContext
        when (val s = _state.value) {
            // back from the "Install unknown apps" switch
            is State.NeedsPermission -> if (app.packageManager.canRequestPackageInstalls()) install(app, s.apk, s.release)
            is State.Downloading, is State.Installing -> return
            else -> {}
        }
        val now = System.currentTimeMillis()
        if (now - lastCheck < OPEN_CHECK_MS) return
        lastCheck = now
        scope.launch { lookUp(app, fetch = unmetered(app)) }
    }

    /** The app left the front: a fetched build installs now when no prompt is needed. */
    fun onClose(ctx: Context) {
        foreground = false
        val ready = _state.value as? State.Ready ?: return
        if (installsSilently(ctx)) install(ctx.applicationContext, ready.apk, ready.release)
    }

    /** The banner's button. */
    fun proceed(ctx: Context) {
        val app = ctx.applicationContext
        when (val s = _state.value) {
            is State.Available -> scope.launch { lock.withLock { fetch(app, s.release) } }
            is State.Ready -> install(app, s.apk, s.release)
            is State.NeedsPermission -> app.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                "package:${app.packageName}".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            is State.Failed -> { lastCheck = System.currentTimeMillis(); scope.launch { lookUp(app, fetch = true) } }
            else -> {}
        }
    }

    private suspend fun lookUp(ctx: Context, fetch: Boolean) = lock.withLock {
        val r = latest(ctx) ?: return@withLock
        if (r.versionCode <= installedVersion(ctx)) { _state.value = State.Idle; return@withLock }
        Log.i(TAG, "update ${r.versionName} (${r.versionCode}) available")
        val cached = cached(ctx, r)
        when {
            cached != null -> _state.value = State.Ready(r, cached)
            fetch -> fetch(ctx, r)
            else -> _state.value = State.Available(r)
        }
    }

    /** Background check (the periodic worker): fetch and install, prompting by notification if Android asks. */
    suspend fun checkAndInstall(ctx: Context): Boolean {
        lookUp(ctx, fetch = true)
        val ready = _state.value as? State.Ready ?: return false
        if (foreground) return true // the banner offers it; installing would close the open app
        install(ctx, ready.apk, ready.release)
        return true
    }

    private fun file(ctx: Context, r: Release) = File(ctx.cacheDir, "update-${r.versionCode}.apk")

    /** A build fetched earlier, still intact. */
    private suspend fun cached(ctx: Context, r: Release): File? = withContext(Dispatchers.IO) {
        val f = file(ctx, r)
        f.takeIf { it.isFile && it.length() == r.size && sha256(it).equals(r.sha256, ignoreCase = true) }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun fetch(ctx: Context, r: Release) = withContext(Dispatchers.IO) {
        // older fetched builds are dead weight once a newer one is published
        ctx.cacheDir.listFiles { f -> f.name.startsWith("update") && f.name.endsWith(".apk") }?.forEach { it.delete() }
        val out = file(ctx, r)
        _state.value = State.Downloading(r, 0f)
        runCatching {
            Api.http.newCall(Request.Builder().url(r.url).build()).execute().use { resp ->
                check(resp.isSuccessful) { "HTTP ${resp.code}" }
                val md = MessageDigest.getInstance("SHA-256")
                var read = 0L
                var shown = 0f
                resp.body!!.byteStream().use { input ->
                    out.outputStream().use { o ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            md.update(buf, 0, n)
                            o.write(buf, 0, n)
                            read += n
                            val progress = if (r.size > 0) (read.toFloat() / r.size).coerceIn(0f, 1f) else 0f
                            if (progress - shown >= .02f) { shown = progress; _state.value = State.Downloading(r, progress) }
                        }
                    }
                }
                val hex = md.digest().joinToString("") { "%02x".format(it) }
                check(hex.equals(r.sha256, ignoreCase = true)) { "checksum mismatch" }
            }
            _state.value = State.Ready(r, out)
        }.onFailure {
            Log.w(TAG, "update download failed", it)
            out.delete()
            _state.value = State.Failed(r, "Couldn’t download the update (${it.message ?: "network"}).")
        }
    }

    /** Android 12+ installs without a prompt once Chorus itself installed the running build. */
    fun installsSilently(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) return false
        val source = runCatching { ctx.packageManager.getInstallSourceInfo(ctx.packageName) }.getOrNull() ?: return false
        val owner = if (Build.VERSION.SDK_INT >= 34) source.updateOwnerPackageName ?: source.installingPackageName else source.installingPackageName
        return owner == ctx.packageName
    }

    private fun install(ctx: Context, apk: File, r: Release) {
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            _state.value = State.NeedsPermission(r, apk)
            if (!foreground) notifyConfirm(ctx, null, r.versionName)
            return
        }
        _state.value = State.Installing(r)
        runCatching {
            val installer = ctx.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(ctx.packageName)
                setSize(apk.length())
                if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("chorus.apk", 0, apk.length()).use { o ->
                        input.copyTo(o)
                        session.fsync(o)
                    }
                }
                val done = PendingIntent.getBroadcast(
                    ctx, id, Intent(ctx, UpdateReceiver::class.java).putExtra("version", r.versionName),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(done.intentSender)
            }
        }.onFailure {
            Log.w(TAG, "update install failed to start", it)
            _state.value = State.Failed(r, "Couldn’t start the install (${it.message ?: "installer"}).")
        }
    }

    /** Android wants a confirmation: show it now if Chorus is open, else a one-tap notification. */
    internal fun confirm(ctx: Context, confirm: Intent?, version: String) {
        (_state.value as? State.Installing)?.let { s -> _state.value = State.Ready(s.release, file(ctx, s.release)) }
        if (foreground && confirm != null && runCatching { ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
        notifyConfirm(ctx, confirm, version)
    }

    internal fun failed(ctx: Context, message: String?) {
        val s = _state.value as? State.Installing ?: return
        val apk = file(ctx, s.release)
        // dismissing Android's dialog is not an error: the banner offers the update again
        _state.value = if (apk.isFile) State.Ready(s.release, apk) else State.Failed(s.release, message ?: "The update didn’t install.")
    }

    /** A one-tap prompt when Android wants the user to confirm (first update, or older Android). */
    fun notifyConfirm(ctx: Context, confirm: Intent?, version: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_DEFAULT))
        val target = if (!ctx.packageManager.canRequestPackageInstalls()) {
            // "Install unknown apps" isn't allowed for Chorus yet: open that switch first
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${ctx.packageName}".toUri())
        } else {
            confirm ?: ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
        } ?: return
        val pi = PendingIntent.getActivity(ctx, 7, target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Chorus update ready")
            .setContentText("Version $version — tap to install")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        if (NotificationManagerCompat.from(ctx).areNotificationsEnabled()) {
            runCatching { NotificationManagerCompat.from(ctx).notify(7001, n) }
        }
    }

    private fun unmetered(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    fun schedule(ctx: Context) {
        val request = PeriodicWorkRequestBuilder<UpdateWork>(12, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("update-check", ExistingPeriodicWorkPolicy.KEEP, request)
    }
}

class UpdateWork(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        runCatching { Updater.checkAndInstall(applicationContext) }
        return Result.success()
    }
}

/** PackageInstaller session results. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val version = intent.getStringExtra("version").orEmpty()
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                Updater.confirm(context, confirm, version)
            }
            PackageInstaller.STATUS_SUCCESS -> Log.i("ChorusUpdate", "updated to $version")
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.w("ChorusUpdate", "update failed: $message")
                Updater.failed(context, message)
            }
        }
    }
}
