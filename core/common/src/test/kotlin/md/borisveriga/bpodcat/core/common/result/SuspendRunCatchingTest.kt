package md.borisveriga.bpodcat.core.common.result

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [suspendRunCatching].
 *
 * The first two cases only confirm it behaves like `runCatching`. The third is the reason the
 * function exists, and is the one that fails against plain `runCatching`.
 */
class SuspendRunCatchingTest {

    @Test
    fun `returns the block's value on success`() = runTest {
        val result = suspendRunCatching { 42 }

        assertEquals(42, result.getOrNull())
    }

    @Test
    fun `captures an ordinary failure`() = runTest {
        val boom = IllegalStateException("boom")

        val result = suspendRunCatching<Int> { throw boom }

        assertTrue(result.isFailure)
        assertEquals(boom, result.exceptionOrNull())
    }

    @Test
    fun `lets cancellation through instead of reporting it as a failure`() = runTest {
        // `started` proves the coroutine reached the suspension point, so the cancellation below
        // genuinely unwinds through the block rather than arriving before it ever ran.
        val started = CompletableDeferred<Unit>()
        val neverCompletes = CompletableDeferred<Unit>()
        var reachedTheLineAfter = false

        val job = launch {
            suspendRunCatching {
                started.complete(Unit)
                neverCompletes.await()
            }
            // Unreachable: cancellation must propagate out of suspendRunCatching, taking this
            // coroutine with it. With plain `runCatching` this line runs — which is exactly the
            // "a cancelled coroutine keeps working" bug the function exists to prevent.
            reachedTheLineAfter = true
        }

        started.await()
        job.cancel()
        job.join()

        assertTrue("the job should have been cancelled", job.isCancelled)
        assertFalse("work continued past a cancelled suspendRunCatching", reachedTheLineAfter)
    }
}
