package garden.vayne.chorus.widget

import android.content.Context
import garden.vayne.chorus.data.Mode

/**
 * Small per-device widget state (CLIENTS.md §3.2): the mode chip (resets to Replace 30 s after
 * last use), each widget instance's open folder, and the last widget switch for Undo. Kept in
 * SharedPreferences so it survives the process being killed between taps.
 */
internal class WidgetState(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("quick_switch_widget", Context.MODE_PRIVATE)

    var mode: Mode
        get() {
            val at = prefs.getLong("mode_at", 0)
            if (System.currentTimeMillis() - at > MODE_RESET_MS) return Mode.Replace
            return runCatching { Mode.valueOf(prefs.getString("mode", null) ?: "Replace") }.getOrDefault(Mode.Replace)
        }
        set(value) {
            prefs.edit().putString("mode", value.name).putLong("mode_at", System.currentTimeMillis()).apply()
        }

    /** Using the chip (or tapping in Add/Remove) keeps the mode for another 30 s. */
    fun touchMode() {
        prefs.edit().putLong("mode_at", System.currentTimeMillis()).apply()
    }

    fun folder(widgetId: Int): String? = prefs.getString("folder_$widgetId", null)

    fun setFolder(widgetId: Int, folder: String?) {
        prefs.edit().apply { if (folder == null) remove("folder_$widgetId") else putString("folder_$widgetId", folder) }.apply()
    }

    fun scope(widgetId: Int, accountId: String?): WidgetScope {
        if (prefs.getString("scope_account_$widgetId", null) != accountId) return WidgetScope()
        val kind = prefs.getString("scope_kind_$widgetId", "all").orEmpty()
        val id = prefs.getString("scope_id_$widgetId", "").orEmpty()
        return if (kind in setOf("group", "subsystem") && id.isNotBlank()) WidgetScope(kind, id) else WidgetScope()
    }

    fun setScope(widgetId: Int, accountId: String, scope: WidgetScope) {
        prefs.edit().putString("scope_account_$widgetId", accountId)
            .putString("scope_kind_$widgetId", scope.kind).putString("scope_id_$widgetId", scope.id)
            .remove("folder_$widgetId").apply()
    }

    fun forget(widgetId: Int) {
        prefs.edit().remove("folder_$widgetId").remove("scope_account_$widgetId")
            .remove("scope_kind_$widgetId").remove("scope_id_$widgetId").apply()
    }

    data class LastSwitch(val opId: String, val label: String, val at: Long)

    var last: LastSwitch?
        get() {
            val id = prefs.getString("undo_op", null) ?: return null
            return LastSwitch(id, prefs.getString("undo_label", "").orEmpty(), prefs.getLong("undo_at", 0))
        }
        set(value) {
            prefs.edit().apply {
                if (value == null) {
                    remove("undo_op"); remove("undo_label"); remove("undo_at")
                } else {
                    putString("undo_op", value.opId); putString("undo_label", value.label); putLong("undo_at", value.at)
                }
            }.commit()
        }

    companion object {
        const val MODE_RESET_MS = 30_000L
        /** The Undo row shows for 10 s; taps still count up to 30 s (a late redraw is harmless). */
        const val UNDO_SHOW_MS = 10_000L
        const val UNDO_ACCEPT_MS = 30_000L
    }
}
