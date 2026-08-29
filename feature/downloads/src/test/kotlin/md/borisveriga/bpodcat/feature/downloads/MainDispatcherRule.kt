package md.borisveriga.bpodcat.feature.downloads

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Swaps the main dispatcher for a test one for the duration of a test.
 *
 * `viewModelScope` is hard-wired to `Dispatchers.Main`, so without this every view model test would
 * fail on a JVM that has no main looper.
 *
 * @property dispatcher the dispatcher to install; unconfined by default so coroutines launched from
 *   a view model run eagerly and assertions can follow the call immediately.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val dispatcher: TestDispatcher = UnconfinedTestDispatcher(),
) : TestWatcher() {

    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
