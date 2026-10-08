package md.borisveriga.megapodcastplayer.feature.player.embedded

import md.borisveriga.megapodcastplayer.core.model.isYouTubeVideoId

/**
 * The page the embedded player screen loads: YouTube's IFrame Player API, one video, and a bridge
 * back to Kotlin.
 *
 * Pure functions, so the page and the two code tables can be tested without a `WebView`, which
 * Robolectric cannot run a real one of. Everything that *does* need the platform is in
 * `EmbeddedVideoScreen`.
 */

/**
 * The name the Kotlin bridge is published to the page under; see [EmbeddedPlayerBridge].
 *
 * Shared between the HTML and the `addJavascriptInterface` call, so the two cannot disagree.
 */
internal const val EMBEDDED_BRIDGE_NAME = "MegaPodcastPlayer"

/**
 * The URL the page is loaded *as*.
 *
 * YouTube's embed refuses to play on a page with no origin, which is what a `data:` URL or a bare
 * `loadData` gives it. Loading the page with this as its base URL gives it an origin the embed
 * accepts, without the app serving anything from anywhere.
 */
internal const val EMBEDDED_PAGE_BASE_URL = "https://www.youtube.com"

/**
 * The JavaScript that pauses the player; evaluated when the screen leaves the front.
 *
 * Guarded, because the API may not have finished loading when the user leaves: a pause on a player
 * that does not exist yet would be a script error in the log and nothing else.
 */
internal const val EMBEDDED_PAUSE_SCRIPT = "if (window.player && player.pauseVideo) player.pauseVideo();"

/** How often the page reports the position, in milliseconds. */
private const val TIME_REPORT_INTERVAL_MS = 1_000

/**
 * The page for one video.
 *
 * The player fills the page; the page is sized by the `WebView` it is in, which the screen draws at
 * the picture's proportions. `playsinline` keeps the picture in that box rather than taking the
 * screen on its own, `rel=0` keeps the end screen to the same channel, and `fs=0` drops the
 * player's own fullscreen button, which a `WebView` cannot honour without a custom view it is not
 * given. Autoplay is asked for and granted: the screen turns the user-gesture requirement off,
 * since the gesture was the tap that opened it.
 *
 * @param videoId the video, which must be a well-formed id: it is written into a script, and a
 *   malformed one would be a script injection.
 * @param startSeconds where to start, in whole seconds; the API takes no finer.
 * @return the page as HTML.
 * @throws IllegalArgumentException for a malformed video id.
 */
internal fun embeddedPlayerHtml(videoId: String, startSeconds: Int): String {
    require(isYouTubeVideoId(videoId)) { "Not a YouTube video id: $videoId" }
    require(startSeconds >= 0) { "A start position cannot be negative: $startSeconds" }
    return """
        <!DOCTYPE html>
        <html>
        <head>
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>
        html, body { margin: 0; padding: 0; background: #000; height: 100%; overflow: hidden; }
        #player { position: absolute; top: 0; left: 0; width: 100%; height: 100%; }
        </style>
        </head>
        <body>
        <div id="player"></div>
        <script>
        var player;
        var tag = document.createElement('script');
        tag.src = 'https://www.youtube.com/iframe_api';
        document.head.appendChild(tag);
        function onYouTubeIframeAPIReady() {
          player = new YT.Player('player', {
            videoId: '$videoId',
            playerVars: { autoplay: 1, playsinline: 1, rel: 0, fs: 0, start: $startSeconds },
            events: {
              onReady: function () { $EMBEDDED_BRIDGE_NAME.onReady(); },
              onStateChange: function (e) { $EMBEDDED_BRIDGE_NAME.onStateChange(e.data); },
              onError: function (e) { $EMBEDDED_BRIDGE_NAME.onError(e.data); }
            }
          });
        }
        setInterval(function () {
          if (player && player.getCurrentTime) {
            $EMBEDDED_BRIDGE_NAME.onTime(player.getCurrentTime(), player.getDuration());
          }
        }, $TIME_REPORT_INTERVAL_MS);
        </script>
        </body>
        </html>
    """.trimIndent()
}

/** What YouTube's player is doing, as its `onStateChange` reports it. */
enum class EmbeddedPlayerState {
    /** Loaded and not yet asked to play; also what the player is before the API has answered. */
    UNSTARTED,

    /** Played to the end. */
    ENDED,

    /** Picture and sound running. */
    PLAYING,

    /** Stopped where it is. */
    PAUSED,

    /** Waiting for bytes. */
    BUFFERING,

    /** A video was cued and not started. */
    CUED,
}

/**
 * The [EmbeddedPlayerState] YouTube's player means by [code], or null for a code the API does not
 * document.
 *
 * The codes are the IFrame Player API's `YT.PlayerState` values, which are a public contract and
 * written out here rather than derived from the enum's order, so a reordering of the enum cannot
 * quietly change what a code means.
 *
 * @param code the `data` of an `onStateChange` event.
 */
internal fun embeddedPlayerStateOf(code: Int): EmbeddedPlayerState? = when (code) {
    STATE_UNSTARTED -> EmbeddedPlayerState.UNSTARTED
    STATE_ENDED -> EmbeddedPlayerState.ENDED
    STATE_PLAYING -> EmbeddedPlayerState.PLAYING
    STATE_PAUSED -> EmbeddedPlayerState.PAUSED
    STATE_BUFFERING -> EmbeddedPlayerState.BUFFERING
    STATE_CUED -> EmbeddedPlayerState.CUED
    else -> null
}

// The IFrame Player API's `YT.PlayerState` values, by name.
private const val STATE_UNSTARTED = -1
private const val STATE_ENDED = 0
private const val STATE_PLAYING = 1
private const val STATE_PAUSED = 2
private const val STATE_BUFFERING = 3
private const val STATE_CUED = 5

/** Why YouTube's player would not play the video, as its `onError` reports it. */
enum class EmbeddedPlayerError {
    /** The video id was not one YouTube recognises. */
    INVALID_VIDEO,

    /** The video exists but its owner has not allowed it to play outside youtube.com. */
    EMBEDDING_NOT_ALLOWED,

    /** The video has been removed, or made private. */
    NOT_FOUND,

    /** The player itself failed; nothing is known about why. */
    PLAYER_FAILED,
}

/**
 * The [EmbeddedPlayerError] YouTube's player means by [code].
 *
 * The codes are the API's documented `onError` values. Two of them, 101 and 150, are the same
 * failure under two numbers — the API's documentation says so — and it is the one failure the
 * official source can realistically meet: a video whose owner has turned embedding off. An
 * undocumented code reads as the player having failed, which is the most that can be said of it.
 *
 * @param code the `data` of an `onError` event.
 */
internal fun embeddedPlayerErrorOf(code: Int): EmbeddedPlayerError = when (code) {
    ERROR_INVALID_PARAMETER -> EmbeddedPlayerError.INVALID_VIDEO
    ERROR_NOT_FOUND -> EmbeddedPlayerError.NOT_FOUND
    ERROR_NOT_EMBEDDABLE, ERROR_NOT_EMBEDDABLE_DISGUISED -> EmbeddedPlayerError.EMBEDDING_NOT_ALLOWED
    else -> EmbeddedPlayerError.PLAYER_FAILED
}

// The IFrame Player API's documented `onError` values, by name.
private const val ERROR_INVALID_PARAMETER = 2
private const val ERROR_NOT_FOUND = 100
private const val ERROR_NOT_EMBEDDABLE = 101

/** The same failure as [ERROR_NOT_EMBEDDABLE] under another number; the API's own documentation says so. */
private const val ERROR_NOT_EMBEDDABLE_DISGUISED = 150
