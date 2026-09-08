package md.borisveriga.megapodcastplayer.core.model.chapters

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Stores and reads the inline chapter list kept on an episode row.
 *
 * This is the app writing to itself rather than a wire format: both ends are the same build, so
 * decoding is strict and unversioned, unlike the user's backup file. A row that will not decode is
 * treated as having no chapters — throwing while mapping a row for a list would cost the user a
 * whole screen over a field nothing else depends on.
 */
object ChapterJson {

    private val json = Json

    private val chapterList = ListSerializer(Chapter.serializer())

    /**
     * Serialises a chapter list for storage.
     *
     * @param chapters the chapters, in order.
     * @return the JSON to store.
     */
    fun encode(chapters: List<Chapter>): String = json.encodeToString(chapterList, chapters)

    /**
     * Reads a stored chapter list.
     *
     * @param raw the stored JSON, or null when the row has none.
     * @return the chapters, or an empty list when there are none or the text will not decode.
     */
    fun decode(raw: String?): List<Chapter> {
        if (raw.isNullOrEmpty()) return emptyList()
        // `runCatching` rather than `suspendRunCatching`: this is not a suspending function, so
        // there is no cancellation to swallow. `WearMessages` decodes the same way and for the same
        // reason — a payload that will not parse is an absence, not an error to propagate.
        return runCatching { json.decodeFromString(chapterList, raw) }.getOrDefault(emptyList())
    }
}
