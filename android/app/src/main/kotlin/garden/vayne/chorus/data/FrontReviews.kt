package garden.vayne.chorus.data

/** The words of the Review page (F3.6). */
object FrontReviews {
    /** "Keep Pixel 8’s", "Keep this device’s". */
    fun keepLabel(device: String): String = when (device) {
        "This device" -> "Keep this device’s"
        "Another device" -> "Keep the other device’s"
        else -> "Keep $device’s"
    }

    /** What "Both are right" means, in names. */
    fun together(a: String, b: String): String = if (a == b) "$a fronted" else "$a and $b fronted together"
}
