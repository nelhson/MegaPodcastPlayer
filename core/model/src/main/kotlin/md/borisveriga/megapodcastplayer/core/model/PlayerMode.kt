package md.borisveriga.megapodcastplayer.core.model

/**
 * Which of its two faces the player shows for an episode that has both.
 *
 * A YouTube episode can be listened to or watched, and which one the user last asked for is a fact
 * about the player rather than about a screen: it decides what a tap on the collapsed bar opens,
 * and it is remembered across launches so the player comes back the way it was left. A feed episode
 * has only sound, so for one of those the mode is simply not consulted — it is kept, and applies
 * again to the next episode with a picture.
 */
enum class PlayerMode {
    /** The player is the sheet with the artwork; a YouTube episode plays as sound. */
    AUDIO,

    /** The player is the video screen, wherever the loaded episode has a picture to show. */
    VIDEO,
}
