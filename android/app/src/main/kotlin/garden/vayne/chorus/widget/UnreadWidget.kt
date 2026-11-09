package garden.vayne.chorus.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import garden.vayne.chorus.MainActivity
import garden.vayne.chorus.R
import garden.vayne.chorus.data.Chorus
import garden.vayne.chorus.data.Inbox
import garden.vayne.chorus.data.InboxRead
import garden.vayne.chorus.data.Model
import garden.vayne.chorus.ui.inboxReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The unread widget (Figma F3.8 "Chorus · 3 unread for Foxglove"): the Notifications count for
 * whoever reads now, the same number as the bottom bar's badge; a tap opens Notifications.
 * Counted from the last read of the inbox; with none on this run yet, it reads it once.
 */
class UnreadWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        scope.launch {
            try {
                val chorus = Chorus.get(ctx)
                val model = chorus.awaitModel()
                val dev = chorus.device
                if (dev != null && Inbox.of(dev.accountId) == null) runCatching { Inbox.refresh(dev, model) }
                for (id in ids) manager.updateAppWidget(id, views(ctx, model))
            } finally { pending.finish() }
        }
    }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        /** Redraw every placed one (the app calls this when the count or the reader changes). */
        fun refreshAll(ctx: Context, model: Model) {
            val manager = AppWidgetManager.getInstance(ctx)
            val ids = manager.getAppWidgetIds(ComponentName(ctx, UnreadWidget::class.java))
            if (ids.isEmpty()) return
            val v = views(ctx, model)
            for (id in ids) manager.updateAppWidget(id, v)
        }

        /** The count and whose it is, from the inbox this run last read. */
        internal fun count(ctx: Context, model: Model): Pair<Int, String?> {
            val reader = inboxReader(model, null)
            val items = Inbox.of(Chorus.get(ctx).device?.accountId).orEmpty()
            val read = reader?.let { model.accountPrefs.inboxRead[it.id] } ?: InboxRead()
            return Inbox.unread(items, read, model.accountPrefs.inboxShows) to reader?.shownName?.takeIf { !model.isPerson }
        }

        private fun views(ctx: Context, model: Model): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget_unread)
            val (n, who) = count(ctx, model)
            v.setTextViewText(R.id.u_count, if (n > 99) "99+" else n.toString())
            v.setTextViewText(R.id.u_for, when { n == 0 -> "caught up"; who != null -> "unread for $who"; else -> "unread" })
            v.setContentDescription(R.id.u_root, if (n == 0) "Chorus, caught up" else "Chorus, $n unread" + (who?.let { " for $it" } ?: ""))
            v.setOnClickPendingIntent(R.id.u_root, PendingIntent.getActivity(ctx, 3,
                Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_TAB, "notifications")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            return v
        }
    }
}
