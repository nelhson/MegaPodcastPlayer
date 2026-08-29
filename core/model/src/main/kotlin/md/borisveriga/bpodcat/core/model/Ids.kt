package md.borisveriga.bpodcat.core.model

import java.security.MessageDigest

/**
 * Derives a [Podcast.id] from a feed URL.
 *
 * The feed URL is the only identifier that every source agrees on: Apple gives us one, a pasted RSS
 * link *is* one. Hashing it keeps ids short, filename-safe and stable across re-adds.
 *
 * @param feedUrl the podcast's RSS feed URL.
 * @return a 40-character lowercase hex SHA-1 digest.
 */
fun podcastIdOf(feedUrl: String): String = sha1(feedUrl.trim())

/**
 * Derives an [Episode.id] from its owning podcast and feed `guid`.
 *
 * Feeds routinely reorder, re-title and re-publish items; the `guid` is the only field publishers
 * are expected to keep stable, so hashing `podcastId + guid` gives an id that survives refreshes and
 * therefore preserves playback position and played state.
 *
 * @param podcastId id of the owning podcast.
 * @param guid the feed item's `<guid>`, or its enclosure URL when no guid is published.
 * @return a 40-character lowercase hex SHA-1 digest.
 */
fun episodeIdOf(podcastId: String, guid: String): String = sha1("$podcastId|${guid.trim()}")

/** Computes a lowercase hex SHA-1 digest of [value]'s UTF-8 bytes. */
private fun sha1(value: String): String =
    MessageDigest.getInstance("SHA-1")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
