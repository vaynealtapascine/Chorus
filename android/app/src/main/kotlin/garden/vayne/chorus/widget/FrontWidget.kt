package garden.vayne.chorus.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.createBitmap
import garden.vayne.chorus.MainActivity
import garden.vayne.chorus.R
import garden.vayne.chorus.data.Chorus
import garden.vayne.chorus.data.FrontScene
import garden.vayne.chorus.data.Model
import garden.vayne.chorus.ui.tones
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The front widget (Figma F3.8): Body's orbit and who is on each of its first three layers, with
 * Switch and Handover (both open the app's switch sheet; Handover starts with the note open).
 * Small sizes show only the innermost layer's faces. Plain RemoteViews (D-058).
 */
class FrontWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        scope.launch {
            try {
                val model = Chorus.get(ctx).awaitModel()
                for (id in ids) manager.updateAppWidget(id, views(ctx, model, manager.getAppWidgetOptions(id)))
            } finally { pending.finish() }
        }
    }

    override fun onAppWidgetOptionsChanged(ctx: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        onUpdate(ctx, manager, intArrayOf(id))
    }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private const val PX = 288

        fun refreshAll(ctx: Context, model: Model) {
            val manager = AppWidgetManager.getInstance(ctx)
            val ids = manager.getAppWidgetIds(ComponentName(ctx, FrontWidget::class.java))
            for (id in ids) manager.updateAppWidget(id, views(ctx, model, manager.getAppWidgetOptions(id)))
        }

        private fun open(ctx: Context, handover: Boolean): PendingIntent = PendingIntent.getActivity(ctx, if (handover) 2 else 1,
            Intent(ctx, MainActivity::class.java).setAction(MainActivity.ACTION_SWITCH).putExtra(MainActivity.EXTRA_HANDOVER, handover)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)

        internal fun views(ctx: Context, model: Model, options: Bundle?): RemoteViews {
            if (Build.VERSION.SDK_INT >= 31) {
                return RemoteViews(mapOf(SizeF(110f, 110f) to small(ctx, model), SizeF(220f, 110f) to large(ctx, model)))
            }
            val wide = (options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) ?: 250) >= 200
            return if (wide) large(ctx, model) else small(ctx, model)
        }

        private fun rings(ctx: Context, model: Model): Pair<List<Pair<String, List<garden.vayne.chorus.data.Subject>>>, String> {
            val account = Chorus.get(ctx).device?.accountId.orEmpty()
            val named = model.namedFronts
            val body = named?.bodyId?.ifEmpty { null } ?: account
            val entries = if (named != null) named.entries(body) else model.current
            val scene = FrontScene.build(model, body, entries, account)
            val layers = scene.all.filter { !it.separate }.take(3).map { l ->
                l.name to l.entries.mapNotNull { model.subject(it.subjectType, it.subjectId) }
            }
            return layers to (named?.fronts?.get(body)?.name?.ifBlank { null } ?: "Body")
        }

        private fun large(ctx: Context, model: Model): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget_front)
            val (layers, name) = rings(ctx, model)
            v.setTextViewText(R.id.f_name, name)
            val labels = listOf(R.id.f_l1 to R.id.f_n1, R.id.f_l2 to R.id.f_n2, R.id.f_l3 to R.id.f_n3)
            labels.forEachIndexed { i, (label, names) ->
                val layer = layers.getOrNull(i)
                v.setViewVisibility(label, if (layer == null) View.GONE else View.VISIBLE)
                v.setViewVisibility(names, if (layer == null) View.GONE else View.VISIBLE)
                if (layer != null) {
                    v.setTextViewText(label, layer.first)
                    v.setTextViewText(names, layer.second.joinToString(", ") { it.name }.ifEmpty { "No one" })
                }
            }
            v.setImageViewBitmap(R.id.f_orbit, orbit(ctx, layers.map { it.second }))
            v.setOnClickPendingIntent(R.id.f_switch, open(ctx, false))
            v.setOnClickPendingIntent(R.id.f_handover, open(ctx, true))
            v.setOnClickPendingIntent(R.id.f_root, PendingIntent.getActivity(ctx, 0,
                Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE))
            if (model.isPerson) v.setViewVisibility(R.id.f_buttons, View.GONE)
            return v
        }

        private fun small(ctx: Context, model: Model): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget_front_small)
            val (layers, _) = rings(ctx, model)
            val inner = layers.firstOrNull()
            v.setTextViewText(R.id.f_l1, inner?.first ?: "Fronting")
            val faces = inner?.second.orEmpty()
            v.setTextViewText(R.id.f_n1, when (faces.size) { 0 -> "No one"; 1 -> faces[0].name; else -> "${faces[0].name} + ${faces.size - 1}" })
            v.setImageViewBitmap(R.id.f_faces, faces(ctx, faces))
            v.setOnClickPendingIntent(R.id.f_root, open(ctx, false))
            return v
        }

        private fun disc(c: Canvas, ctx: Context, s: garden.vayne.chorus.data.Subject, cx: Float, cy: Float, r: Float, edge: Int) {
            val dark = Avatars.dark(ctx)
            val t = tones(s.color, dark)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.color = t.ring.toArgb(); c.drawCircle(cx, cy, r, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = r * .12f; p.color = edge; c.drawCircle(cx, cy, r, p)
            p.style = Paint.Style.FILL; p.color = t.avatarInk.toArgb(); p.textAlign = Paint.Align.CENTER
            p.textSize = r * .9f; p.typeface = android.graphics.Typeface.DEFAULT_BOLD
            c.drawText(s.glyph.take(2), cx, cy - (p.descent() + p.ascent()) / 2, p)
        }

        /** Concentric layer rings with each face on its ring, as in the app's Fronts cards. */
        private fun orbit(ctx: Context, rings: List<List<garden.vayne.chorus.data.Subject>>): Bitmap {
            val bmp = createBitmap(PX, PX)
            val c = Canvas(bmp)
            val accent = ctx.getColor(R.color.w_accent)
            val faint = ctx.getColor(R.color.w_ink3)
            val bg = ctx.getColor(R.color.w_bg)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f }
            val radii = listOf(.32f, .62f, .92f).map { it * PX / 2 }
            for (i in rings.indices.take(3)) {
                p.color = if (i == 2) faint else accent
                p.alpha = if (i == 0) 255 else 150
                p.pathEffect = if (i == 0) null else DashPathEffect(floatArrayOf(10f, 8f), 0f)
                c.drawCircle(PX / 2f, PX / 2f, radii[i], p)
            }
            val face = PX * .13f
            rings.take(3).forEachIndexed { i, faces ->
                faces.take(6).forEachIndexed { n, s ->
                    val angle = if (i == 0) Math.toRadians(180.0 + n * 360.0 / faces.size.coerceAtLeast(1)) else Math.toRadians(-60.0 + i * 90 + n * 50.0)
                    val d = if (i == 0) (if (faces.size == 1) 0f else face * .9f) else radii[i]
                    disc(c, ctx, s, PX / 2f + (d * cos(angle)).toFloat(), PX / 2f + (d * sin(angle)).toFloat(), face, bg)
                }
            }
            return bmp
        }

        private fun faces(ctx: Context, faces: List<garden.vayne.chorus.data.Subject>): Bitmap {
            val r = 60f
            val shown = faces.take(4).ifEmpty { return createBitmap(1, 1) }
            val bmp = createBitmap((r * 2 + (shown.size - 1) * r * 1.4f).toInt() + 8, (r * 2).toInt() + 8)
            val c = Canvas(bmp)
            shown.forEachIndexed { i, s -> disc(c, ctx, s, 4 + r + i * r * 1.4f, 4 + r, r, ctx.getColor(R.color.w_bg)) }
            return bmp
        }
    }
}
