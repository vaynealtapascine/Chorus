package garden.vayne.chorus.data

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Things the app says once, as a toast above the bottom bar (F10.7), and the words of the
 * connection banner (F2.5, F10.3). The wording lives here so it can be tested without a UI.
 */
object Notices {
    sealed interface Notice {
        /** The server said slow down (`429 rate_limited`, or the sync socket's `rate_limited` frame). */
        data class SlowDown(val seconds: Int?) : Notice
    }

    private val notices = MutableSharedFlow<Notice>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val flow: SharedFlow<Notice> = notices

    fun slowDown(seconds: Int?) { notices.tryEmit(Notice.SlowDown(seconds)) }

    /** `Retry-After` as whole seconds; our server sends seconds, never an HTTP date. */
    fun retryAfter(header: String?): Int? = header?.trim()?.toIntOrNull()?.takeIf { it in 1..3600 }

    fun slowDownText(secondsLeft: Int?): String =
        if (secondsLeft == null || secondsLeft <= 0) "Catching its breath. Try again in a moment."
        else "Catching its breath. Try again in $secondsLeft ${if (secondsLeft == 1) "second" else "seconds"}."

    /** What the connection banner shows: nothing, "changes wait here" (warn), or the server not answering (danger). */
    enum class Connection { None, Away, Asleep }

    /** Failed connection attempts since the last welcome after which a reachable network means the server is down. */
    const val ASLEEP_AFTER = 3

    /**
     * A fresh start that is still connecting shows nothing; once a connection has failed or dropped,
     * no network reads as away, and repeated failures on a working network as the server asleep.
     */
    fun connection(status: Status, failures: Int, network: Boolean): Connection = when {
        status != Status.Offline && status != Status.Connecting -> Connection.None
        failures <= 0 -> Connection.None
        !network -> Connection.Away
        failures >= ASLEEP_AFTER -> Connection.Asleep
        else -> Connection.Away
    }

    /** A Chorus Home install (its invite pinned the certificate) is "home"; the public server never is (D-095). */
    fun connectionText(kind: Connection, homeInstall: Boolean): String? = when (kind) {
        Connection.None -> null
        Connection.Away -> if (homeInstall) "Away from home. Changes wait on this phone." else "Offline. Changes wait on this phone."
        Connection.Asleep -> if (homeInstall) "Chorus Home is asleep. Everything you do is saved here and sent later."
            else "The server isn't answering. Everything you do is saved here and sent later."
    }
}
