package garden.vayne.chorus.data

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ShortcutManager
import android.os.Build
import android.util.Log
import androidx.work.WorkManager
import java.io.File

/**
 * Erasing what a signed-out phone still holds (D-152): afterwards the app is a fresh install's.
 * [Chorus.erase] is the only caller, and only for a `wipe_at` that has passed or the enrolment
 * rule (D-153: an invite other than a device link of the kept copy's account).
 */
internal object Erase {
    enum class Reason { WipeAtPassed, Enrolment, Resumed }

    class Step(val name: String, val run: () -> Unit)

    /**
     * Every step runs even when one fails. The marker is set first and cleared only when all of
     * them worked, so an erase cut short (the process killed, a step failing) runs again at the
     * next start. Returns the names of the steps that failed.
     */
    fun run(markPending: () -> Unit, steps: List<Step>, clearPending: () -> Unit,
        log: (String, Throwable) -> Unit = { name, e -> Log.w("ChorusErase", "erase step $name failed", e) }): List<String> {
        markPending()
        val failed = steps.mapNotNull { step ->
            runCatching(step.run).exceptionOrNull()?.let { e -> log(step.name, e); step.name }
        }
        if (failed.isEmpty()) clearPending()
        return failed
    }

    private const val MARKER_PREFS = "chorus.erase"
    private const val PENDING = "pending"

    /** An erase was started and didn't finish. */
    fun pending(ctx: Context): Boolean = ctx.getSharedPreferences(MARKER_PREFS, Context.MODE_PRIVATE).getBoolean(PENDING, false)

    fun markPending(ctx: Context) {
        ctx.getSharedPreferences(MARKER_PREFS, Context.MODE_PRIVATE).edit().putBoolean(PENDING, true).commit()
    }

    fun clearPending(ctx: Context) {
        ctx.getSharedPreferences(MARKER_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        ctx.deleteSharedPreferences(MARKER_PREFS)
    }

    /** Unique work that serves the account; the app's own update check and the erase job stay. */
    val WORK = listOf("sync", "sync-periodic", "notify-check", "chorus-uploads")

    /** Preferences a fresh install also has, or that belong to libraries that clean up after
     * themselves (WorkManager's own, Firebase's token state, which deleteToken clears). */
    internal fun keepsPrefs(name: String): Boolean =
        name == MARKER_PREFS || name.startsWith("androidx.work") || name.startsWith("com.google") || name.startsWith("Firebase")

    /** The on-device steps, in order: no more work, push, the store and its key, the device key,
     * files, preferences, shown notifications, launcher shortcuts. The caller has closed the store. */
    fun deviceSteps(ctx: Context, pushState: String?): List<Step> = listOf(
        Step("work") { val wm = WorkManager.getInstance(ctx); WORK.forEach(wm::cancelUniqueWork) },
        Step("push") { Push.forgetLocally(ctx, pushState) },
        Step("store") {
            for (name in Store.NAMES) {
                ctx.deleteDatabase(name)
                check(!ctx.getDatabasePath(name).exists()) { "$name is still there" }
            }
        },
        Step("store key") { DatabaseKey.forget(ctx) },
        Step("device key") { DeviceKey.forget() },
        Step("files") {
            for (dir in listOf(ctx.filesDir, ctx.cacheDir)) dir.listFiles()?.forEach { check(it.deleteRecursively()) { "${it.name} is still there" } }
        },
        Step("preferences") {
            val dir = File(ctx.applicationInfo.dataDir, "shared_prefs")
            for (file in dir.listFiles().orEmpty()) {
                val name = file.name.removeSuffix(".xml")
                if (keepsPrefs(name)) continue
                // clear first: the context keeps loaded preferences in memory
                ctx.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
                ctx.deleteSharedPreferences(name)
            }
        },
        Step("notifications") { ctx.getSystemService(NotificationManager::class.java)?.cancelAll() },
        Step("shortcuts") {
            val manager = ctx.getSystemService(ShortcutManager::class.java) ?: return@Step
            manager.removeAllDynamicShortcuts()
            if (Build.VERSION.SDK_INT >= 30) {
                val cached = manager.getShortcuts(ShortcutManager.FLAG_MATCH_CACHED).map { it.id }
                if (cached.isNotEmpty()) manager.removeLongLivedShortcuts(cached)
            }
            garden.vayne.chorus.widget.PinnedShortcuts.forget()
        },
    )
}
