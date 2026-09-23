package garden.vayne.chorus.widget

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.LruCache
import androidx.core.graphics.createBitmap
import garden.vayne.chorus.ui.tones
import androidx.compose.ui.graphics.toArgb

/**
 * Small pre-rendered avatars for RemoteViews (CLIENTS.md §3.3: widget bitmaps are capped, so keep
 * them tiny): sigil or initial on a soft disc, ringed in the member's theme-adapted colour.
 * Folders are rounded squares with the member count.
 */
internal object Avatars {
    private const val PX = 108
    private val cache = LruCache<String, Bitmap>(256)

    fun dark(ctx: Context): Boolean =
        ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    fun member(ctx: Context, glyph: String, color: String): Bitmap = draw(ctx, "m", glyph, color)

    fun folder(ctx: Context, count: Int, color: String): Bitmap = draw(ctx, "f", count.toString(), color)

    private fun draw(ctx: Context, shape: String, text: String, color: String): Bitmap {
        val dark = dark(ctx)
        val key = "$shape|$text|$color|$dark"
        cache.get(key)?.let { return it }
        val t = tones(color, dark)
        val surface2 = ctx.getColor(garden.vayne.chorus.R.color.w_surface2)
        val bmp = createBitmap(PX, PX)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val ring = PX * 0.05f
        val box = RectF(ring, ring, PX - ring, PX - ring)
        if (shape == "f") {
            p.color = t.tint.toArgb()
            c.drawRoundRect(box, PX * 0.24f, PX * 0.24f, p)
        } else {
            p.color = surface2
            c.drawOval(box, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = ring * 1.4f
            p.color = t.ring.toArgb()
            c.drawOval(box, p)
            p.style = Paint.Style.FILL
        }
        p.color = t.name.toArgb()
        p.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        p.textAlign = Paint.Align.CENTER
        p.textSize = PX * (if (text.length > 2 && shape == "f") 0.32f else 0.42f)
        val y = PX / 2f - (p.descent() + p.ascent()) / 2f
        c.drawText(text, PX / 2f, y, p)
        cache.put(key, bmp)
        return bmp
    }
}
