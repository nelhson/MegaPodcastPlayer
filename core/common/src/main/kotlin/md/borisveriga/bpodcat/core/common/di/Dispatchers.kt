package md.borisveriga.bpodcat.core.common.di

import javax.inject.Qualifier

/** The coroutine dispatchers BPodcat injects, so tests can swap in a test dispatcher. */
enum class BPodcatDispatcher {
    /** Blocking IO: disk, database and network. */
    IO,

    /** CPU-bound work: feed diffing and parsing. */
    Default,
}

/**
 * Qualifies an injected `CoroutineDispatcher`.
 *
 * @property dispatcher which dispatcher to inject.
 */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class Dispatcher(val dispatcher: BPodcatDispatcher)

/**
 * Qualifies the application-scoped `CoroutineScope` used for work that must outlive any one screen
 * (download bookkeeping, playback-position writes).
 */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class ApplicationScope
