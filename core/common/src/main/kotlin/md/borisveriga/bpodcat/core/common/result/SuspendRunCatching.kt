package md.borisveriga.bpodcat.core.common.result

import kotlin.coroutines.cancellation.CancellationException

/**
 * Like [runCatching], but never swallows coroutine cancellation.
 *
 * `runCatching` catches [Throwable], and in a coroutine that includes [CancellationException] —
 * the exception the machinery throws *through* a suspending call to unwind it. Catching it turns
 * a cancelled coroutine into one that reports a failure and carries on: the enclosing job is
 * already cancelled, so nothing stops the remaining work, and a loop that logs the "failure" and
 * continues will run to completion long after its owner is gone.
 *
 * The same applies to `catch (e: Exception)`, which is why every broad catch around a suspending
 * call in this codebase either uses this function or rethrows [CancellationException] explicitly.
 * Detekt's `TooGenericExceptionCaught` and `SwallowedException` rules are enabled to keep it
 * that way.
 *
 * [block] is not marked `suspend`, but this function is inline, so the lambda body is compiled
 * into the caller and may suspend freely.
 *
 * @param block the work to run.
 * @return [Result.success] with the block's value, or [Result.failure] for any non-cancellation
 *   throwable it raised.
 * @throws CancellationException if the calling coroutine was cancelled, rethrown unchanged.
 */
// The generic catch is the entire point of the function: it exists so that no other site in the
// codebase has to write one. Rethrowing the cancellation unchanged is likewise deliberate.
@Suppress("TooGenericExceptionCaught", "RethrowCaughtException", "SwallowedException")
suspend inline fun <T> suspendRunCatching(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (throwable: Throwable) {
        Result.failure(throwable)
    }
