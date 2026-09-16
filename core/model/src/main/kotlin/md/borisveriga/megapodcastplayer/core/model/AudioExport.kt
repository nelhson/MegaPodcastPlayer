package md.borisveriga.megapodcastplayer.core.model

/**
 * Names and formats for a show's downloaded audio exported as ordinary files.
 *
 * A download is not a file anyone else can open: it lives in Media3's cache as a set of spans
 * indexed by the episode's audio URL. Exporting writes each one out again as a single file, and the
 * two things that need deciding for that — what the file is called and what container it is — are
 * decided here, in a pure module, so they can be tested without a device.
 *
 * The container has to be *sniffed* rather than looked up. Nothing records it: a YouTube episode is
 * whichever stream the resolver picked on the day (usually M4A, sometimes WebM), and a feed's
 * enclosure `type` is read only to check that the item is audio.
 */

/** Characters no mainstream file system or storage provider accepts in a name. */
private val FORBIDDEN_FILE_NAME_CHARACTERS = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")

/**
 * The longest name, in UTF-8 bytes, most file systems will store.
 *
 * Bytes rather than characters because that is how ext4 and FAT's long names are limited, and a
 * Cyrillic title is two bytes a letter.
 */
private const val MAX_FILE_NAME_BYTES = 255

/** The fewest digits a position is padded to, so a short playlist still sorts as a long one will. */
private const val MIN_POSITION_DIGITS = 3

/** Used when a title is nothing but characters that had to be removed. */
private const val FALLBACK_EPISODE_NAME = "Episode"

/** Used when a show's title is nothing but characters that had to be removed. */
private const val FALLBACK_FOLDER_NAME = "Podcast"

/** Separates the position from the title: `001 - Title.m4a`. */
private const val POSITION_SEPARATOR = " - "

/**
 * The file name one exported episode is written under.
 *
 * Numbered, because the point of exporting a playlist is usually to listen to it somewhere else, in
 * order — and a file manager, a car stereo or a USB player sorts by name. The number is padded to
 * as many digits as the largest position needs (never fewer than three), so `010` sorts after
 * `009` rather than after `001`.
 *
 * @param position this episode's place in the export, counted from 1.
 * @param total how many episodes the export has, which decides the padding.
 * @param title the episode's title; cleaned of characters a file system would refuse.
 * @param extension the container's extension, without the dot.
 * @return a name safe to hand to a storage provider, at most [MAX_FILE_NAME_BYTES] bytes long.
 */
fun exportFileName(position: Int, total: Int, title: String, extension: String): String {
    val digits = maxOf(MIN_POSITION_DIGITS, maxOf(total, position).toString().length)
    val prefix = position.toString().padStart(digits, '0') + POSITION_SEPARATOR
    val suffix = ".$extension"
    val budget = MAX_FILE_NAME_BYTES - prefix.utf8Size() - suffix.utf8Size()
    val cleaned = cleanFileNamePart(title, FALLBACK_EPISODE_NAME).truncateToUtf8Bytes(budget)
        // Truncating can expose a trailing space or dot the cleaning had already dealt with.
        .trimEnd(' ', '.')
        .ifEmpty { FALLBACK_EPISODE_NAME }
    return prefix + cleaned + suffix
}

/**
 * The folder a show's episodes are exported into, inside whichever folder the user picked.
 *
 * @param showTitle the show's title.
 * @return a folder name safe to hand to a storage provider.
 */
fun exportFolderName(showTitle: String): String =
    cleanFileNamePart(showTitle, FALLBACK_FOLDER_NAME)
        .truncateToUtf8Bytes(MAX_FILE_NAME_BYTES)
        .trimEnd(' ', '.')
        .ifEmpty { FALLBACK_FOLDER_NAME }

/**
 * An exported file's name without its position: `First talk.m4a` for `001 - First talk.m4a`.
 *
 * Positions move. A new video joins a playlist at `001`, and a deleted episode pulls everything
 * after it up by one, so the number is no way to recognise a file written by an earlier export. The
 * rest of the name is.
 *
 * @param fileName a file's display name.
 * @return the name after the number, or null when [fileName] does not start with the digits and
 *   separator [exportFileName] writes.
 */
fun exportFileNameWithoutPosition(fileName: String): String? {
    val separator = fileName.indexOf(POSITION_SEPARATOR)
    if (separator < MIN_POSITION_DIGITS) return null
    if (!fileName.substring(0, separator).all { it in '0'..'9' }) return null
    return fileName.substring(separator + POSITION_SEPARATOR.length)
}

/**
 * Removes what a file system would refuse, and what would make a name awkward to read.
 *
 * Leading and trailing dots go as well as spaces: a leading dot hides the file on most systems, and
 * Windows silently drops a trailing one, which would make two exports of the same file disagree.
 *
 * @param raw the text to clean.
 * @param fallback returned when nothing is left.
 * @return the cleaned text, never empty.
 */
private fun cleanFileNamePart(raw: String, fallback: String): String =
    raw.replace(FORBIDDEN_FILE_NAME_CHARACTERS, " ")
        .replace(whitespaceRun, " ")
        .trim(' ', '.')
        .ifEmpty { fallback }

/** How many bytes this string takes in UTF-8. */
private fun String.utf8Size(): Int = toByteArray(Charsets.UTF_8).size

/**
 * Shortens this string to at most [maxBytes] of UTF-8, never splitting a character.
 *
 * Walks by code point, so a surrogate pair — an emoji in a video title — is kept or dropped whole.
 *
 * @param maxBytes the budget.
 * @return the longest prefix that fits.
 */
private fun String.truncateToUtf8Bytes(maxBytes: Int): String {
    if (utf8Size() <= maxBytes) return this
    var used = 0
    var end = 0
    while (end < length) {
        val codePoint = codePointAt(end)
        val width = Character.charCount(codePoint)
        val bytes = substring(end, end + width).utf8Size()
        if (used + bytes > maxBytes) break
        used += bytes
        end += width
    }
    return substring(0, end)
}

/**
 * The container a downloaded episode is in, as told by its first bytes.
 *
 * @property extension what an exported file of this kind is called, without the dot.
 * @property mimeType what the storage provider is told the file is.
 */
enum class AudioContainer(val extension: String, val mimeType: String) {

    /** MPEG-4 audio: what YouTube usually serves, and many feeds. */
    M4A("m4a", "audio/mp4"),

    /** WebM/Opus: YouTube's other audio stream. */
    WEBM("webm", "audio/webm"),

    /** MP3: most podcast enclosures. */
    MP3("mp3", "audio/mpeg"),

    /** Ogg, usually Vorbis or Opus. */
    OGG("ogg", "audio/ogg"),

    /**
     * Something none of the above recognise.
     *
     * Still exported: the bytes are the user's either way, and a file with an honest unknown
     * extension is better than one that is skipped or that claims a format it is not.
     */
    UNKNOWN("bin", "application/octet-stream"),
    ;

    companion object {

        /** How many leading bytes [sniff] needs to tell every known container apart. */
        const val HEADER_BYTES = 12

        /**
         * Identifies a container from the start of a file.
         *
         * @param header the file's first bytes; fewer than [HEADER_BYTES] is allowed, and simply
         *   rules out whatever needs more.
         * @return the container, or [UNKNOWN].
         */
        fun sniff(header: ByteArray): AudioContainer = when {
            // ISO base media: a box size, then the `ftyp` box type.
            header.matchesAt(ISO_BMFF_TYPE_OFFSET, FTYP) -> M4A

            header.matchesAt(0, EBML_MAGIC) -> WEBM

            header.matchesAt(0, OGG_MAGIC) -> OGG

            header.matchesAt(0, ID3_MAGIC) || header.isMpegFrameSync() -> MP3

            else -> UNKNOWN
        }

        private const val ISO_BMFF_TYPE_OFFSET = 4
        private val FTYP = "ftyp".toByteArray(Charsets.US_ASCII)
        private val EBML_MAGIC = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte())
        private val OGG_MAGIC = "OggS".toByteArray(Charsets.US_ASCII)
        private val ID3_MAGIC = "ID3".toByteArray(Charsets.US_ASCII)

        /** The top three bits of an MPEG audio frame header's second byte, all set in a sync. */
        private const val FRAME_SYNC_MASK = 0xE0

        /** The mask for the byte values that are unsigned, since Kotlin's bytes are signed. */
        private const val UNSIGNED_BYTE_MASK = 0xFF

        /** `11111111 111xxxxx`: an MP3 with no ID3 tag starts straight on a frame. */
        private fun ByteArray.isMpegFrameSync(): Boolean =
            size >= 2 &&
                (this[0].toInt() and UNSIGNED_BYTE_MASK) == UNSIGNED_BYTE_MASK &&
                (this[1].toInt() and FRAME_SYNC_MASK) == FRAME_SYNC_MASK

        /** Whether [expected] appears in this array starting at [offset]. */
        private fun ByteArray.matchesAt(offset: Int, expected: ByteArray): Boolean =
            size >= offset + expected.size &&
                expected.indices.all { index -> this[offset + index] == expected[index] }
    }
}
