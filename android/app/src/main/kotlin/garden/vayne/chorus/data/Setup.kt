package garden.vayne.chorus.data

import android.content.Context

/**
 * A new system's setup (F1.2–F1.4) is owed until it's finished or skipped through: kept per
 * account on this device, with the name typed on the welcome screen so setup can name the
 * system (`system.set`) once the replica is open.
 */
object Setup {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("chorus.setup", 0)

    fun begin(ctx: Context, accountId: String, name: String) {
        prefs(ctx).edit().putString("pending:$accountId", name).apply()
    }

    /** The name typed at welcome while setup is owed (empty if none was), else null. */
    fun pending(ctx: Context, accountId: String): String? = prefs(ctx).getString("pending:$accountId", null)

    fun done(ctx: Context, accountId: String) {
        prefs(ctx).edit().remove("pending:$accountId").apply()
    }
}
