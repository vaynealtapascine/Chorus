package garden.vayne.chorus.data

import kotlin.math.roundToInt

/** Geometry uses the already EXIF-oriented decoded bitmap. No IO or pixel allocation. */
object ProfileCrop {
    data class Rect(val x: Int, val y: Int, val width: Int, val height: Int)
    fun rect(width: Int, height: Int, banner: Boolean, zoom: Float, x: Float, y: Float): Rect {
        require(width > 0 && height > 0)
        val aspect = if (banner) 3f else 1f
        val baseWidth = minOf(width.toFloat(), height * aspect)
        val w = (baseWidth / zoom.coerceIn(1f, 3f)).roundToInt().coerceIn(1, width)
        val h = (w / aspect).roundToInt().coerceIn(1, height)
        return Rect(((width - w) * x.coerceIn(0f, 1f)).roundToInt(),
            ((height - h) * y.coerceIn(0f, 1f)).roundToInt(), w, h)
    }
}
