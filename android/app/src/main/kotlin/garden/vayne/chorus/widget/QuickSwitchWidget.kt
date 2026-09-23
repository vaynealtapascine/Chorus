package garden.vayne.chorus.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import garden.vayne.chorus.MainActivity
import garden.vayne.chorus.R
import garden.vayne.chorus.SearchActivity
import garden.vayne.chorus.data.Chorus
import garden.vayne.chorus.data.Mode
import garden.vayne.chorus.data.Model
import garden.vayne.chorus.data.Front
import garden.vayne.chorus.ui.ago
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * The quick-switch home-screen widget (CLIENTS.md §3), as plain RemoteViews (D-058). A tap writes
 * through the same replica as the app (op → encrypted store → sync work), then redraws.
 */
class QuickSwitchWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, manager: AppWidgetManager, ids: IntArray) {
        val chorus = Chorus.get(ctx)
        val pending = goAsync()
        scope.launch {
            try {
                val model = chorus.awaitModel()
                for (id in ids) manager.updateAppWidget(id, views(ctx, id, model))
                manager.notifyAppWidgetViewDataChanged(ids, R.id.w_grid)
            } finally {
                pending.finish()
            }
        }
    }

    override fun onDeleted(ctx: Context, ids: IntArray) {
        val state = WidgetState(ctx)
        ids.forEach(state::forget)
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        val action = intent.action
        if (action == null || !action.startsWith(PREFIX)) return super.onReceive(ctx, intent)
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val state = WidgetState(ctx)
        val pending = goAsync()
        scope.launch {
            try {
                when (action) {
                    ACTION_MODE -> state.mode = state.mode.next()
                    ACTION_BACK -> {
                        val model = Chorus.get(ctx).awaitModel()
                        state.setFolder(widgetId, state.folder(widgetId)?.let { model.group(it)?.parentId })
                    }
                    ACTION_TILE -> onTile(ctx, state, widgetId, intent)
                    ACTION_UNDO -> undo(ctx, state)
                    ACTION_REFRESH -> Unit
                }
            } catch (e: Exception) {
                Log.w(TAG, "widget action failed", e)
            } finally {
                refreshAll(ctx)
                pending.finish()
            }
        }
    }

    private suspend fun onTile(ctx: Context, state: WidgetState, widgetId: Int, intent: Intent) {
        val type = intent.getStringExtra(EXTRA_TYPE) ?: return
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        if (type == "folder") {
            state.setFolder(widgetId, id)
            return
        }
        val chorus = Chorus.get(ctx)
        val model = chorus.awaitModel()
        val subject = model.subject(type, id) ?: return
        val mode = state.mode
        val label = Front.tap(chorus, subject, mode)
        Front.undo.value?.let { state.last = WidgetState.LastSwitch(it.opId, label, it.at) }
        if (mode != Mode.Replace) state.touchMode()
        // leave the folder after a switch so the next glance shows the root again
        state.setFolder(widgetId, null)
        scheduleClear(ctx)
    }

    private suspend fun undo(ctx: Context, state: WidgetState) {
        val last = state.last ?: return
        state.last = null
        if (System.currentTimeMillis() - last.at > WidgetState.UNDO_ACCEPT_MS) return
        val chorus = Chorus.get(ctx)
        chorus.awaitModel()
        chorus.create("front.retract", chorus.newId(), JSONObject().put("target_op_id", last.opId))
        Front.dismiss(last.opId)
    }

    /** Redraws after the Undo row expires; lateness is fine because Undo checks its own deadline. */
    class ClearUndo(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
        override fun doWork(): Result {
            applicationContext.sendBroadcast(action(applicationContext, ACTION_REFRESH))
            return Result.success()
        }
    }

    companion object {
        private const val TAG = "ChorusWidget"
        private const val PREFIX = "garden.vayne.chorus.widget."
        const val ACTION_TILE = PREFIX + "TILE"
        const val ACTION_MODE = PREFIX + "MODE"
        const val ACTION_BACK = PREFIX + "BACK"
        const val ACTION_UNDO = PREFIX + "UNDO"
        const val ACTION_REFRESH = PREFIX + "REFRESH"
        const val EXTRA_TYPE = "type"
        const val EXTRA_ID = "id"

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        private fun action(ctx: Context, action: String, widgetId: Int = 0): Intent =
            Intent(ctx, QuickSwitchWidget::class.java).setAction(action)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)

        private fun broadcast(ctx: Context, action: String, widgetId: Int, mutable: Boolean = false): PendingIntent =
            PendingIntent.getBroadcast(
                ctx, action.hashCode() * 31 + widgetId, action(ctx, action, widgetId),
                PendingIntent.FLAG_UPDATE_CURRENT or if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE,
            )

        private fun scheduleClear(ctx: Context) {
            WorkManager.getInstance(ctx).enqueue(
                OneTimeWorkRequestBuilder<ClearUndo>().setInitialDelay(WidgetState.UNDO_SHOW_MS + 500, TimeUnit.MILLISECONDS).build(),
            )
        }

        /** Redraw every Chorus widget (after a tap, a sync, or the Undo row expiring). */
        fun refreshAll(ctx: Context, model: Model? = null) {
            val manager = AppWidgetManager.getInstance(ctx)
            val ids = manager.getAppWidgetIds(ComponentName(ctx, QuickSwitchWidget::class.java))
            if (ids.isEmpty()) return
            scope.launch {
                val m = model ?: Chorus.get(ctx).awaitModel()
                for (id in ids) manager.updateAppWidget(id, views(ctx, id, m))
                manager.notifyAppWidgetViewDataChanged(ids, R.id.w_grid)
            }
        }

        internal fun views(ctx: Context, widgetId: Int, model: Model): RemoteViews {
            val state = WidgetState(ctx)
            val v = RemoteViews(ctx.packageName, R.layout.widget_quick_switch)
            val signedIn = Chorus.get(ctx).device != null

            val front = model.current.filter { it.level == "front" }
            val since = model.since?.let { " · " + ago(it).replace("just now", "now") }.orEmpty()
            v.setTextViewText(R.id.w_front, if (front.isEmpty()) "No one fronting" else model.frontLabel() + since)
            val open = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE,
            )
            v.setOnClickPendingIntent(R.id.w_front, open)

            val mode = state.mode
            v.setTextViewText(R.id.w_mode, mode.label)
            v.setInt(R.id.w_mode, "setBackgroundResource", if (mode == Mode.Replace) R.drawable.w_chip else R.drawable.w_chip_on)
            v.setTextColor(R.id.w_mode, ctx.getColor(if (mode == Mode.Replace) R.color.w_ink2 else R.color.w_accent))
            v.setOnClickPendingIntent(R.id.w_mode, broadcast(ctx, ACTION_MODE, widgetId))
            v.setOnClickPendingIntent(
                R.id.w_search,
                PendingIntent.getActivity(
                    ctx, 1, Intent(ctx, SearchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )

            val last = state.last
            val showUndo = last != null && System.currentTimeMillis() - last.at < WidgetState.UNDO_SHOW_MS
            v.setViewVisibility(R.id.w_undo_row, if (showUndo) View.VISIBLE else View.GONE)
            if (showUndo) {
                v.setTextViewText(R.id.w_undo_label, last!!.label)
                v.setOnClickPendingIntent(R.id.w_undo, broadcast(ctx, ACTION_UNDO, widgetId))
            }

            val folder = state.folder(widgetId)?.let { model.group(it) }
            v.setViewVisibility(R.id.w_crumb, if (folder != null) View.VISIBLE else View.GONE)
            if (folder != null) {
                v.setTextViewText(R.id.w_crumb, "‹  " + model.groupPath(folder))
                v.setOnClickPendingIntent(R.id.w_crumb, broadcast(ctx, ACTION_BACK, widgetId))
            }

            val service = Intent(ctx, TileService::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            service.data = Uri.parse(service.toUri(Intent.URI_INTENT_SCHEME))
            v.setRemoteAdapter(R.id.w_grid, service)
            v.setEmptyView(R.id.w_grid, R.id.w_empty)
            v.setTextViewText(R.id.w_empty, if (signedIn) "No members yet" else "Open Chorus to set up")
            v.setPendingIntentTemplate(R.id.w_grid, broadcast(ctx, ACTION_TILE, widgetId, mutable = true))
            return v
        }

        /** Hook the widget to model rebuilds (called once from the Application). */
        fun install(ctx: Context) {
            val app = ctx.applicationContext
            var lastKey: Any? = null
            Chorus.get(app).onModel = { m ->
                // only redraw when something the widget shows changed
                val key = listOf(m.current, m.members.map { Triple(it.id, it.shownName, it.color to it.glyph) }, m.groups, m.membership, m.switches.size)
                if (key != lastKey) {
                    lastKey = key
                    refreshAll(app, m)
                }
            }
        }
    }
}
