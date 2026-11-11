package garden.vayne.chorus.data

import org.json.JSONObject

/**
 * This device was signed out from another one (D-152, D-153): when, and when what it still holds is
 * erased ([wipeAt], server time; null when the server gave no times, and then nothing is erased on
 * a timer). [offset] is the server's clock minus ours when it was recorded, where the app knew it.
 */
data class SignedOutRecord(val at: Long? = null, val wipeAt: Long? = null, val offset: Long = 0) {
    /** The server's deadline has passed by our clock, corrected by [offset]. */
    fun due(now: Long): Boolean = wipeAt != null && now + offset >= wipeAt

    /** How long until [due], or null with no deadline. */
    fun delay(now: Long): Long? = wipeAt?.let { (it - (now + offset)).coerceAtLeast(0) }

    fun toJson(): String = JSONObject().apply {
        at?.let { put("at", it) }
        wipeAt?.let { put("wipe_at", it) }
        put("offset", offset)
    }.toString()

    companion object {
        /** Tolerant: anything unreadable is a sign-out without times. */
        fun fromJson(s: String): SignedOutRecord = runCatching {
            val j = JSONObject(s)
            fun opt(key: String) = if (j.has(key) && !j.isNull(key)) j.getLong(key) else null
            SignedOutRecord(opt("at"), opt("wipe_at"), j.optLong("offset"))
        }.getOrDefault(SignedOutRecord())
    }
}

object SignedOut {
    /**
     * Whether a session renewal's refusal means this device is signed out for good (D-153): the
     * server's `signed_out` (with its times), a bare `forbidden` (an older server, or a device it no
     * longer has) or `bad_key` (the device key no longer matches). Anything else is transient.
     */
    fun refusal(e: ApiException, offset: Long = 0): SignedOutRecord? = when (e.code) {
        "signed_out" -> {
            val times = e.body?.optJSONObject("signed_out")
            fun opt(key: String) = times?.takeIf { it.has(key) && !it.isNull(key) }?.optLong(key)
            SignedOutRecord(opt("at"), opt("wipe_at"), offset)
        }
        "forbidden", "bad_key" -> SignedOutRecord(offset = offset)
        else -> null
    }

    /** The welcome screen's one quiet line for a signed-out phone; [date] formats a server time. */
    fun notice(record: SignedOutRecord, date: (Long) -> String): String {
        val wipe = record.wipeAt ?: return "This phone was signed out from another device."
        return "This phone was signed out from another device. What it still holds will be erased on ${date(wipe)}, " +
            "unless it's linked to the same account again before then."
    }

    /** How an invite meets a kept copy (D-153). */
    enum class Enrol {
        /** Nothing kept: enrol as usual. */
        Fresh,
        /** A device link of the kept copy's account: enrol and carry on with what is kept. */
        PickUp,
        /** Anything else: erase the kept copy first, once the person has agreed. */
        EraseFirst,
    }

    /**
     * [keptAccount] is the account of the copy a signed-out phone still holds (null: none); [peek]
     * what the invite is for. A device invite whose account the server didn't say can't be told
     * apart from another account's, so it erases first too.
     */
    fun enrol(keptAccount: String?, peek: InvitePeek?): Enrol = when {
        keptAccount == null -> Enrol.Fresh
        peek?.kind == "device" && peek.accountId == keptAccount -> Enrol.PickUp
        else -> Enrol.EraseFirst
    }

    fun eraseFirstText(peek: InvitePeek?): String =
        (if (peek?.kind == "device") "That link is for another account. " else "That invite starts a new account. ") +
            "This phone still holds the copy it kept when it was signed out, including anything that never synced. " +
            "Using this link erases it first."
}
