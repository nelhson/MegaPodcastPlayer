package md.borisveriga.megapodcastplayer.core.youtube

/**
 * Says whether this device can decode a picture of a given kind.
 *
 * YouTube offers one video in several codecs, and a rendition in one the phone has no decoder for
 * does not fail: the player leaves the track unselected and plays the sound under a black frame.
 * The resolver therefore asks before it chooses. It asks through this interface rather than the
 * platform's codec list because what can be decoded is the player's knowledge, not the extractor's;
 * `:core:media` supplies the answer.
 */
fun interface VideoDecoderCheck {

    /**
     * Whether a picture can be decoded here.
     *
     * @param mimeType the picture's MIME type, such as `video/avc`.
     * @param height the frame height in pixels.
     * @return true when the device has a decoder for it.
     */
    fun canDecode(mimeType: String, height: Int): Boolean
}

/**
 * The codec families YouTube encodes a picture in, in the order this app prefers them.
 *
 * H.264 first because every phone decodes it in hardware; VP9 next, which most do; AV1 last, which
 * many only decode in software — a decoder the platform lists as available and that then drops
 * frames at 1080p.
 *
 * @property mimeType the MIME type a decoder is looked up by, or null when the family is unknown.
 */
internal enum class VideoCodec(val mimeType: String?) {
    AVC("video/avc"),
    VP9("video/x-vnd.on2.vp9"),

    /** A codec string this app does not recognise; ranked beside VP9, and never filtered out. */
    UNKNOWN(null),
    AV1("video/av01"),
}

/**
 * Reads the codec family out of an extractor's codec string.
 *
 * The strings are RFC 6381 codec parameters — `avc1.64001F`, `vp9` or `vp09.00.40.08`,
 * `av01.0.08M.08` — and only the part before the first dot names the family.
 *
 * @param codec the extractor's codec string, which may be null or blank.
 * @return the family, or [VideoCodec.UNKNOWN] for anything else.
 */
internal fun videoCodecOf(codec: String?): VideoCodec {
    val family = codec.orEmpty().trim().lowercase().substringBefore('.')
    return when {
        family.startsWith("avc") -> VideoCodec.AVC
        family.startsWith("vp9") || family.startsWith("vp09") -> VideoCodec.VP9
        family.startsWith("av01") -> VideoCodec.AV1
        else -> VideoCodec.UNKNOWN
    }
}

/**
 * Drops the renditions this device cannot decode.
 *
 * A rendition of unknown codec is kept: not knowing is not a reason to refuse it. And when nothing
 * at all passes, everything is kept — the check may be wrong, the player may still manage, and a
 * picture that fails in the player is handed back to sound and said so, which is a better end than
 * a video this app claims has no picture.
 *
 * Extracted as a top-level function so it can be tested without a device.
 *
 * @param candidates the playable renditions, from [playableVideoCandidates].
 * @param decoders says what this device can decode.
 * @return the decodable renditions, in the order given.
 */
internal fun decodableVideoCandidates(
    candidates: List<VideoCandidate>,
    decoders: VideoDecoderCheck,
): List<VideoCandidate> {
    val decodable = candidates.filter { candidate ->
        val mimeType = candidate.codec.mimeType ?: return@filter true
        decoders.canDecode(mimeType, candidate.height)
    }
    return decodable.ifEmpty { candidates }
}
