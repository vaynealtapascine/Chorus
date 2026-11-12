package garden.vayne.chorus.data

/**
 * The sync socket's lifecycle (CLIENTS.md §2.3) as a pure reducer. [Chorus] feeds it every event
 * under one lock and carries out the effects it returns, so the status, the socket, the backoff and
 * a renewal in flight never disagree across the main and store threads. Sockets are numbered: a
 * frame or close from a socket that is no longer the current one changes nothing.
 *
 * A server that refuses this device for good (signed out from another device, or its key no
 * longer matching; [SignedOut.refusal]) leaves it [Status.SignedOut] (D-148, D-153): no socket and
 * no retries until it is linked again; [Chorus] keeps when, and erases on time (D-152).
 */
object SyncLink {
    const val FIRST_BACKOFF = 1_000L
    const val MAX_BACKOFF = 5 * 60_000L

    data class State(
        val status: Status = Status.Loading,
        /** The socket we own, by number; null while there is none. */
        val socket: Long? = null,
        val lastSocket: Long = 0,
        /** A session renewal after the server said `unauthenticated` is under way. */
        val renewing: Boolean = false,
        val backoff: Long = FIRST_BACKOFF,
        /** Sockets lost or refused since the last welcome (the connection banner, F2.5/F10.3). */
        val failures: Int = 0,
        /** The app is in front or a sync work holds a lease. */
        val wanted: Boolean = false,
    ) {
        /** Live on [socket]: only then may a frame from it say we are caught up. */
        fun liveOn(socket: Long): Boolean = status == Status.Live && this.socket == socket
    }

    /** How a session renewal ended: a fresh session, a definite refusal, or anything else. */
    enum class Renewal { Renewed, Refused, Failed }

    sealed interface Event {
        /** The store opened: whether this phone is linked, and whether it was signed out before. */
        data class Loaded(val device: Boolean, val signedOut: Boolean) : Event
        /** The app came to the front or went away, or a sync work took or gave back its lease. */
        data class Wanted(val wanted: Boolean) : Event
        /** The network came back or someone asked: try now instead of waiting out the backoff. */
        data object ConnectNow : Event
        /** The backoff timer ran out. */
        data object RetryDue : Event
        data class Welcome(val socket: Long) : Event
        data class Unauthenticated(val socket: Long) : Event
        data class Dropped(val socket: Long) : Event
        data class RenewalDone(val result: Renewal) : Event
        data object StorageFailed : Event
    }

    sealed interface Effect {
        data class Open(val socket: Long) : Effect
        data class Close(val socket: Long, val reason: String) : Effect
        /** Book a retry after about [after] ms (the caller adds jitter), replacing a booked one. */
        data class Retry(val after: Long) : Effect
        data object CancelRetry : Effect
        /** Ask for a fresh session; its end comes back as [Event.RenewalDone]. */
        data object Renew : Effect
        /** The socket is gone: forget typing, tell core, nothing is caught up. */
        data object Disconnected : Effect
    }

    data class Step(val state: State, val effects: List<Effect> = emptyList())

    fun reduce(s: State, e: Event): Step = when (e) {
        is Event.Loaded -> {
            if (s.status == Status.StorageError) Step(s)
            else {
                val (closed, effects) = drop(s, "account changed")
                val status = when {
                    !e.device -> Status.NoDevice
                    e.signedOut -> Status.SignedOut
                    else -> Status.Offline
                }
                val next = closed.copy(status = status, renewing = false, backoff = FIRST_BACKOFF, failures = 0)
                open(next, effects + Effect.CancelRetry)
            }
        }
        is Event.Wanted -> {
            val next = s.copy(wanted = e.wanted)
            if (e.wanted) reduce(next, Event.ConnectNow)
            else {
                val (closed, effects) = drop(next, "background")
                Step(closed.copy(status = if (next.socket != null) Status.Offline else closed.status), effects + Effect.CancelRetry)
            }
        }
        Event.ConnectNow ->
            if (!connectable(s)) Step(s) else open(s.copy(backoff = FIRST_BACKOFF), listOf(Effect.CancelRetry))
        Event.RetryDue -> if (!connectable(s)) Step(s) else open(s, emptyList())
        is Event.Welcome ->
            if (s.socket != e.socket || s.status != Status.Connecting && s.status != Status.Live) Step(s)
            else Step(s.copy(status = Status.Live, failures = 0, backoff = FIRST_BACKOFF))
        is Event.Unauthenticated ->
            if (s.socket != e.socket || s.renewing) Step(s)
            else {
                val (closed, effects) = drop(s, "unauthenticated")
                Step(closed.copy(status = Status.Offline, renewing = true), effects + Effect.CancelRetry + Effect.Renew)
            }
        is Event.Dropped ->
            if (s.socket != e.socket) Step(s)
            else {
                val (closed, effects) = drop(s, null)
                retry(closed.copy(status = Status.Offline, failures = s.failures + 1), effects)
            }
        is Event.RenewalDone -> when (e.result) {
            // from a socket's renewal or any other signed-in call: this phone is signed out
            Renewal.Refused ->
                if (s.status == Status.StorageError || s.status == Status.NoDevice) Step(s.copy(renewing = false))
                else {
                    val (closed, effects) = drop(s, "signed out")
                    Step(closed.copy(status = Status.SignedOut, renewing = false), effects + Effect.CancelRetry)
                }
            // a renewal another call made changes nothing here
            Renewal.Renewed -> if (!s.renewing) Step(s) else reduce(s.copy(renewing = false), Event.ConnectNow)
            Renewal.Failed -> if (!s.renewing) Step(s)
                else retry(s.copy(renewing = false, failures = s.failures + 1), emptyList())
        }
        Event.StorageFailed -> {
            val (closed, effects) = drop(s, "storage unavailable")
            Step(closed.copy(status = Status.StorageError, renewing = false), effects + Effect.CancelRetry)
        }
    }

    private fun connectable(s: State) = s.wanted && s.status == Status.Offline && s.socket == null && !s.renewing

    private fun open(s: State, effects: List<Effect>): Step {
        if (!connectable(s)) return Step(s, effects)
        val n = s.lastSocket + 1
        return Step(s.copy(status = Status.Connecting, socket = n, lastSocket = n), effects + Effect.Open(n))
    }

    /** Book the next try (offline and still wanted), doubling the wait up to five minutes. */
    private fun retry(s: State, effects: List<Effect>): Step =
        if (!connectable(s)) Step(s, effects)
        else Step(s.copy(backoff = minOf(s.backoff * 2, MAX_BACKOFF)), effects + Effect.Retry(s.backoff))

    /** Let go of the current socket, closing it when [reason] is given (null: it closed itself). */
    private fun drop(s: State, reason: String?): Pair<State, List<Effect>> {
        val socket = s.socket ?: return s to emptyList()
        val effects = listOfNotNull(reason?.let { Effect.Close(socket, it) }, Effect.Disconnected)
        return s.copy(socket = null) to effects
    }
}
