package md.borisveriga.megapodcastplayer.feature.settings

import md.borisveriga.megapodcastplayer.core.model.PodcastSource
import md.borisveriga.megapodcastplayer.core.model.backup.BackupFile
import md.borisveriga.megapodcastplayer.core.model.backup.BackupPodcast
import md.borisveriga.megapodcastplayer.core.model.backup.OpmlDecodeResult
import md.borisveriga.megapodcastplayer.core.model.backup.OpmlFeed
import md.borisveriga.megapodcastplayer.core.model.youTubePlaylistIdOrNull

/**
 * Turns a decoded OPML file into a backup that carries subscriptions and nothing else.
 *
 * This one function is the whole of ADD-3's import. Everything an import has to do beyond parsing
 * — fetching twenty feeds one at a time, carrying on past the one that 404s and naming it
 * afterwards, surviving the process being killed halfway, merging with shows already present
 * instead of duplicating them — the restorer does already, and it does it because a restore has the
 * same shape. Writing an importer would mean answering all of that a second time, and the two
 * answers would disagree the first time either changed.
 *
 * So the import is a restore of a file the user never had: podcasts, no episode state, no queue, no
 * downloads, no moments. Those absences are not omissions to be filled in later — they are what
 * OPML is, and the confirmation dialog says so before anything is fetched.
 *
 * @param importedAtMs when the import was started; the synthetic backup's `exportedAtMs`, which is
 *   never shown to anyone but is what makes the document valid.
 * @return the file to hand to the restorer.
 */
internal fun OpmlDecodeResult.Decoded.asBackupFile(importedAtMs: Long): BackupFile = BackupFile(
    exportedAtMs = importedAtMs,
    podcasts = feeds.mapIndexed { index, feed -> feed.asBackupPodcast(index, importedAtMs) },
)

/**
 * One OPML row as a subscription.
 *
 * The [source][BackupPodcast.source] is derived from the URL rather than from the file's `type`
 * attribute, and that is the one judgement here. Every exporter writes `type="rss"`, including this
 * one — a YouTube playlist's Atom feed *is* an XML document at an `http` URL, and an app that has
 * never heard of this one should be able to subscribe to it. But this app plays a YouTube show
 * differently from an RSS one, so on the way back in the URL is asked rather than the attribute:
 * `youTubePlaylistIdOrNull` is the exact inverse of the URL this app minted when the show was
 * added, so a library exported from here and imported back comes home as what it was.
 *
 * @param index the row's place in the document, which becomes its place in the library. An OPML
 *   file's order is the exporting app's, and it is the only ordering information the format has.
 * @param importedAtMs stands in for "when the user added this show", which OPML does not record.
 * @return the subscription.
 */
private fun OpmlFeed.asBackupPodcast(index: Int, importedAtMs: Long): BackupPodcast = BackupPodcast(
    feedUrl = feedUrl,
    source = if (youTubePlaylistIdOrNull(feedUrl) != null) {
        PodcastSource.YOUTUBE
    } else {
        PodcastSource.RSS
    },
    title = title,
    sortOrder = index,
    addedAtMs = importedAtMs,
)
