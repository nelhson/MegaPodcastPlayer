package md.borisveriga.bpodcat.core.wearprotocol

import kotlinx.serialization.json.Json

/**
 * Turns the phone <-> watch contract into bytes and back.
 *
 * The Data Layer moves opaque `ByteArray`s, so both sides need one agreed encoding; JSON is used
 * rather than a binary format because a payload is a few hundred bytes at most, and being able to
 * read one straight out of `adb shell dumpsys` is worth more here than the bytes saved.
 *
 * Decoding never throws. A watch running an older build will meet fields it does not know, and a
 * corrupt or truncated payload is always possible over Bluetooth; in both cases the right answer is
 * "ignore this one and wait for the next", not a crash inside a Data Layer callback.
 */
object WearMessages {

    private val json = Json {
        // An older peer must survive fields added by a newer one; this is the whole reason the two
        // APKs can be updated independently.
        ignoreUnknownKeys = true
        // Defaults are written out so that a peer whose defaults differ still reads the real value.
        encodeDefaults = true
    }

    /** Serialises a command for [WearPaths.COMMAND]. */
    fun encodeCommand(command: WearCommand): ByteArray =
        json.encodeToString(WearCommand.serializer(), command).encodeToByteArray()

    /**
     * Parses a command received on [WearPaths.COMMAND].
     *
     * @return the command, or null if the payload was not one this build understands.
     */
    fun decodeCommand(bytes: ByteArray): WearCommand? = runCatching {
        json.decodeFromString(WearCommand.serializer(), bytes.decodeToString())
    }.getOrNull()

    /** Serialises a playback snapshot for [WearPaths.NOW_PLAYING]. */
    fun encodeSnapshot(snapshot: NowPlayingSnapshot): ByteArray =
        json.encodeToString(NowPlayingSnapshot.serializer(), snapshot).encodeToByteArray()

    /**
     * Parses a playback snapshot received on [WearPaths.NOW_PLAYING].
     *
     * @return the snapshot, or null if the payload was unreadable.
     */
    fun decodeSnapshot(bytes: ByteArray): NowPlayingSnapshot? = runCatching {
        json.decodeFromString(NowPlayingSnapshot.serializer(), bytes.decodeToString())
    }.getOrNull()

    /** Serialises the phone's offline library for [WearPaths.OFFLINE_LIBRARY]. */
    fun encodeLibrary(library: OfflineLibrary): ByteArray =
        json.encodeToString(OfflineLibrary.serializer(), library).encodeToByteArray()

    /**
     * Parses an offline library received on [WearPaths.OFFLINE_LIBRARY].
     *
     * @return the library, or null if the payload was unreadable. A watch that cannot read the list
     *   offers nothing to copy, which is the same as a phone with nothing downloaded — and is a
     *   great deal better than a crash inside a Data Layer callback.
     */
    fun decodeLibrary(bytes: ByteArray): OfflineLibrary? = runCatching {
        json.decodeFromString(OfflineLibrary.serializer(), bytes.decodeToString())
    }.getOrNull()
}
