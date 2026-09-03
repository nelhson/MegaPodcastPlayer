package md.borisveriga.bpodcat.core.wearprotocol

import kotlinx.serialization.Serializable

/**
 * An episode the phone has downloaded, offered to the watch.
 *
 * Deliberately thinner than [QueuedEpisode] plus a duration: this list exists to be *chosen from*,
 * and the two facts that decide the choice are what it is and how much room it will take. Everything
 * else the watch needs it learns when the audio arrives.
 *
 * @property id the episode, as the watch will name it when asking for a copy.
 * @property title episode title.
 * @property showTitle the owning podcast, which is also what colours the row.
 * @property durationMs how long it runs, or `0` when the feed never said.
 * @property sizeBytes how much will cross the link, as the phone measures it on disk. Zero when the
 *   phone cannot tell, which is not a reason to refuse the transfer — only a reason not to promise
 *   how long it will take.
 */
@Serializable
data class OfflineEpisode(
    val id: String,
    val title: String,
    val showTitle: String = "",
    val durationMs: Long = 0L,
    val sizeBytes: Long = 0L,
)

/**
 * Everything the phone currently holds offline, published as a data item.
 *
 * A data item rather than an answer to a question: the watch needs this list the moment it is
 * opened, often with the phone in a pocket and asleep, and the Data Layer keeps the last one it was
 * given. It is republished whenever the phone's downloads change.
 *
 * The list is capped by the publisher rather than here; see `OfflineLibraryPublisher` in the phone
 * app for the size a data item may be.
 *
 * @property episodes what can be copied, newest download first.
 */
@Serializable
data class OfflineLibrary(
    val episodes: List<OfflineEpisode> = emptyList(),
)
