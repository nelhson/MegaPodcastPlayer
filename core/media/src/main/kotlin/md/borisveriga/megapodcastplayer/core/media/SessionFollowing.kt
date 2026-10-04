package md.borisveriga.megapodcastplayer.core.media

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import md.borisveriga.megapodcastplayer.core.common.result.suspendRunCatching

/**
 * Following the playback session for as long as somebody is watching, across the session going
 * away.
 *
 * [PlaybackConnection] used to connect once. A connect that failed ended the flow on a
 * disconnected state that nothing ever replaced, and a session that went away afterwards left the
 * last snapshot standing — a bar showing an episode that a player no longer held. Here the two are
 * one loop: connect, follow until the session ends, connect again.
 *
 * Apart from the connection so that the loop can be tested without a `MediaController`.
 */

/** The longest wait between two attempts to connect. */
private const val MAX_RECONNECT_DELAY_MS = 30_000L

/** The wait after the first failed attempt; doubled for each one after it. */
private const val FIRST_RECONNECT_DELAY_MS = 1_000L

/** The attempt past which the wait stops doubling, which also keeps the shift in range. */
private const val MAX_DOUBLINGS = 5

/**
 * How long to wait before trying to connect again.
 *
 * One second, doubling to a ceiling of thirty: quick enough that a service the system is still
 * bringing up is caught on the second try, slow enough that one that cannot be bound at all costs
 * two attempts a minute.
 *
 * @param failures how many attempts in a row have failed before this wait, from zero.
 * @return the wait in milliseconds.
 */
internal fun reconnectDelayMs(failures: Int): Long =
    (FIRST_RECONNECT_DELAY_MS shl failures.coerceIn(0, MAX_DOUBLINGS)).coerceAtMost(MAX_RECONNECT_DELAY_MS)

/**
 * The playback state, followed across failed connects and lost sessions.
 *
 * A failed connect is said — disconnected, with the failure's message, and *not* restoring, so a
 * screen holding its face for the queue to come back lets go — and tried again after
 * [retryDelayMs]. A session that ends under a live collector is said as [PlaybackState.isRestoring]
 * and connected to again: binding is what recreates the service, and the service puts the persisted
 * queue back as it starts, so the state that follows is the restored one.
 *
 * Never completes; it runs until its collector goes.
 *
 * @param S the session handle, a `MediaController` outside tests.
 * @param connect connects, or throws when the service cannot be reached.
 * @param states the states of one session, completing when that session ends.
 * @param retryDelayMs the wait before the next attempt, given how many have failed in a row.
 * @return the states of every session in turn, with the gaps between them said.
 */
internal fun <S : Any> followSession(
    connect: suspend () -> S,
    states: (S) -> Flow<PlaybackState>,
    retryDelayMs: (failures: Int) -> Long = ::reconnectDelayMs,
): Flow<PlaybackState> = flow {
    var failures = 0
    while (true) {
        val session = suspendRunCatching { connect() }
            .onFailure { error ->
                // No service means no playback, but the UI must still render — as idle, not as a
                // crash. A cancelled collector is not a failure and never reaches here.
                emit(PlaybackState(isConnected = false, errorMessage = error.message))
            }
            .getOrNull()
        if (session == null) {
            delay(retryDelayMs(failures++))
            continue
        }
        failures = 0
        emitAll(states(session))
        // The session ended and somebody is still watching. What it last said is no longer true
        // of anything; what the next one will say is not known yet.
        emit(PlaybackState(isRestoring = true))
        // Paced like a failure, so a session that ends the moment it starts cannot spin this loop.
        delay(retryDelayMs(0))
    }
}
