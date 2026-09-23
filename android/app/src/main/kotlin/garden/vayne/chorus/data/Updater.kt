package garden.vayne.chorus.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * In-app updates over the tailnet (CLIENTS.md §5a, D-051): the server publishes the APK and its
 * sha256; the app downloads it (unmetered network by default), verifies it, and installs through a
 * PackageInstaller session. Once Chorus installed itself, Android 12+ lets later updates go
 * through without a prompt; otherwise a notification asks for one tap.
 */
object Updater {
    private const val TAG = "ChorusUpdate"
    private const val CHANNEL = "updates"

    data class Release(val versionCode: Long, val versionName: String, val sha256: String, val size: Long, val url: String)

    fun installedVersion(ctx: Context): Long {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    }

    suspend fun latest(ctx: Context): Release? {
        val dev = Chorus.get(ctx).awaitDevice() ?: return null
        val j = runCatching { Api.call("GET", dev.base, "/android/latest", null) }.getOrNull() ?: return null
        return Release(
            j.optLong("version_code"), j.optString("version_name"), j.optString("sha256"), j.optLong("size"),
            j.optString("url").ifEmpty { "${dev.base}/download/android" },
        )
    }

    /** Check, and if a newer build is published, download and install it. */
    suspend fun checkAndInstall(ctx: Context): Boolean {
        val r = latest(ctx) ?: return false
        if (r.versionCode <= installedVersion(ctx)) return false
        Log.i(TAG, "update ${r.versionName} (${r.versionCode}) available")
        val apk = download(ctx, r) ?: return false
        install(ctx, apk, r)
        return true
    }

    private suspend fun download(ctx: Context, r: Release): File? = withContext(Dispatchers.IO) {
        val out = File(ctx.cacheDir, "update.apk")
        runCatching {
            Api.http.newCall(Request.Builder().url(r.url).build()).execute().use { resp ->
                check(resp.isSuccessful) { "HTTP ${resp.code}" }
                val md = MessageDigest.getInstance("SHA-256")
                resp.body!!.byteStream().use { input ->
                    out.outputStream().use { o ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            md.update(buf, 0, n)
                            o.write(buf, 0, n)
                        }
                    }
                }
                val hex = md.digest().joinToString("") { "%02x".format(it) }
                check(hex.equals(r.sha256, ignoreCase = true)) { "checksum mismatch" }
            }
            out
        }.onFailure {
            Log.w(TAG, "update download failed", it)
            out.delete()
        }.getOrNull()
    }

    private fun install(ctx: Context, apk: File, r: Release) {
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
    }

    /** A one-tap prompt when Android wants the user to confirm (first update, or older Android). */
    fun notifyConfirm(ctx: Context, confirm: Intent?, version: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_DEFAULT))
        val target = if (!ctx.packageManager.canRequestPackageInstalls()) {
            // "Install unknown apps" isn't allowed for Chorus yet: open that switch first
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${ctx.packageName}".toUri())
        } else {
            confirm
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
                Updater.notifyConfirm(context, confirm, version)
            }
            PackageInstaller.STATUS_SUCCESS -> Log.i("ChorusUpdate", "updated to $version")
            else -> Log.w("ChorusUpdate", "update failed: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
        }
    }
}
