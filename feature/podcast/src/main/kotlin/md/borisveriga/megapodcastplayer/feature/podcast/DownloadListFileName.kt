package md.borisveriga.megapodcastplayer.feature.podcast

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import md.borisveriga.megapodcastplayer.core.model.exportFolderName

/**
 * The file name suggested for one show's exported download list.
 */

/** ISO order, hyphen-separated, so every document provider accepts the name as it is. */
private val FILE_NAME_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

/**
 * Suggests a name for one show's download list.
 *
 * The show's title goes through [exportFolderName], which removes what a file system refuses, so a
 * show called `AC/DC: Live` still produces a name the picker accepts.
 *
 * @param showTitle the show's title.
 * @param date the day of the export, so a folder of exports sorts by date.
 * @return e.g. `Podlodka Podcast downloads 2026-09-16.md`.
 */
internal fun downloadListFileName(showTitle: String, date: LocalDate): String =
    exportFolderName(showTitle) + " downloads " + FILE_NAME_DATE.format(date) + ".md"
