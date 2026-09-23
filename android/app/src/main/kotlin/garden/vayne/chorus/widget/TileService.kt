package garden.vayne.chorus.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import garden.vayne.chorus.R
import garden.vayne.chorus.data.Chorus
import kotlinx.coroutines.runBlocking

/** Feeds the widget grid. Runs on a binder thread, so blocking on the replica is allowed here. */
class TileService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        Factory(applicationContext, intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0))

    private class Factory(private val ctx: Context, private val widgetId: Int) : RemoteViewsFactory {
        private var tiles: List<WidgetTile> = emptyList()
        private var here: Set<Pair<String, String>> = emptySet()

        override fun onCreate() = Unit
        override fun onDestroy() = Unit

        override fun onDataSetChanged() {
            val model = runBlocking { Chorus.get(ctx).awaitModel() }
            tiles = widgetTiles(model, WidgetState(ctx).folder(widgetId))
            here = model.current.map { it.subjectType to it.subjectId }.toSet()
        }

        override fun getCount() = tiles.size
        override fun getViewTypeCount() = 1
        override fun hasStableIds() = true
        override fun getLoadingView(): RemoteViews? = null

        override fun getItemId(position: Int): Long = when (val t = tiles.getOrNull(position)) {
            is WidgetTile.Subject -> (t.type + t.id).hashCode().toLong()
            is WidgetTile.Folder -> ("f" + t.id).hashCode().toLong()
            null -> position.toLong()
        }

        override fun getViewAt(position: Int): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget_tile)
            val fill = Intent()
            when (val t = tiles.getOrNull(position)) {
                is WidgetTile.Subject -> {
                    v.setImageViewBitmap(R.id.t_avatar, Avatars.member(ctx, t.glyph, t.color))
                    v.setTextViewText(R.id.t_name, t.name)
                    val on = (t.type to t.id) in here
                    v.setInt(R.id.t_root, "setBackgroundResource", if (on) R.drawable.w_chip_on else R.drawable.w_tile)
                    v.setContentDescription(R.id.t_root, if (on) "${t.name}, fronting" else t.name)
                    fill.putExtra(QuickSwitchWidget.EXTRA_TYPE, t.type).putExtra(QuickSwitchWidget.EXTRA_ID, t.id)
                }
                is WidgetTile.Folder -> {
                    v.setImageViewBitmap(R.id.t_avatar, Avatars.folder(ctx, t.count, t.color))
                    v.setTextViewText(R.id.t_name, "${t.name} ›")
                    v.setInt(R.id.t_root, "setBackgroundResource", R.drawable.w_tile)
                    v.setContentDescription(R.id.t_root, "${t.name}, ${t.count} members")
                    fill.putExtra(QuickSwitchWidget.EXTRA_TYPE, "folder").putExtra(QuickSwitchWidget.EXTRA_ID, t.id)
                }
                null -> Unit
            }
            v.setOnClickFillInIntent(R.id.t_root, fill)
            return v
        }
    }
}
