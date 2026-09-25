package garden.vayne.chorus.widget

import android.content.Context
import org.json.JSONArray

/** Manual, per-account member pin order on this device. Stale IDs are ignored by tile builders. */
object WidgetPins {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("quick_switch_pins", Context.MODE_PRIVATE)

    fun read(ctx: Context, accountId: String?): List<String> {
        if (accountId.isNullOrBlank()) return emptyList()
        val raw = prefs(ctx).getString(accountId, null) ?: return emptyList()
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).mapNotNull { a.optString(it).takeIf(String::isNotBlank) }.distinct()
        }.getOrDefault(emptyList())
    }

    fun write(ctx: Context, accountId: String?, ids: List<String>) {
        if (accountId.isNullOrBlank()) return
        prefs(ctx).edit().putString(accountId, JSONArray(ids.distinct()).toString()).apply()
        QuickSwitchWidget.refreshAll(ctx)
    }
}
