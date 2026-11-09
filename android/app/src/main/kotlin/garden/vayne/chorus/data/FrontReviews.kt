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

    /** "Phone says Foxglove, laptop says Amarynthe" (F11.1): what each device recorded. */
    fun disagree(aDevice: String, aWho: String, bDevice: String, bWho: String): String {
        fun quiet(d: String) = if (d == "This device" || d == "Another device") d.lowercase() else d
        return "$aDevice says $aWho, ${quiet(bDevice)} says $bWho"
    }

    /** The Needs-you row's detail and time for [r], from the switches this model holds (unconfigured fronts). */
    fun summary(model: Model, r: FrontReview, me: String?): Pair<String, Long>? {
        val a = model.switches.firstOrNull { it.id == r.switchA } ?: return null
        val b = model.switches.firstOrNull { it.id == r.switchB } ?: return null
        fun device(s: SwitchRow) = s.deviceId?.let { model.deviceNames[it] ?: if (it == me) "This device" else null } ?: "Another device"
        fun who(s: SwitchRow) = s.resultingFront.joinToString(" & ") { model.subject(it.subjectType, it.subjectId)?.name ?: "Someone" }.ifEmpty { "No one" }
        return disagree(device(a), who(a), device(b), who(b)) to maxOf(a.occurredAt, b.occurredAt)
    }
}
