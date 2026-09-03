package md.borisveriga.bpodcat.wear.data

import md.borisveriga.bpodcat.core.wearprotocol.NowPlayingSnapshot

/**
 * Where playback has reached, for something drawn long after the phone last spoke.
 *
 * The app's screen does not need this: it stamps each snapshot with the watch's own
 * [android.os.SystemClock.elapsedRealtime] on arrival and measures forward from there, which needs
 * no agreement between the two devices' clocks. The watch-face surfaces cannot — the tile and the
 * complication are built from the *cached* data item, with no record of when it landed — so they
 * fall back to the phone's own wall clock, published inside the snapshot.
 *
 * The two wall clocks are independent in principle. In practice both are set from the network, and
 * an error of a few seconds is invisible on a progress bar six pixels tall. The extrapolation is
 * capped regardless: a tile can sit unlooked-at for a day, and a bar that had quietly walked to the
 * end would be a confident lie where a stale one is merely old.
 *
 * @param snapshot what the phone published.
 * @param nowMs the watch's wall clock.
 * @return the position to draw; the published one unchanged when nothing is playing, since a paused
 *   episode does not move.
 */
internal fun extrapolatedPositionMs(snapshot: NowPlayingSnapshot, nowMs: Long): Long {
    if (!snapshot.isPlaying) return snapshot.positionMs

    val sincePublish = (nowMs - snapshot.publishedAtMs).coerceIn(0L, MAX_EXTRAPOLATION_MS)
    return snapshot.positionAfter(sincePublish)
}

/**
 * The furthest a watch-face surface may run ahead of the last thing the phone said.
 *
 * Half an hour is longer than any gap between publishes while an episode is actually playing, and
 * short enough that a surface left overnight is visibly stale rather than wrong.
 */
private const val MAX_EXTRAPOLATION_MS = 30 * 60 * 1_000L
