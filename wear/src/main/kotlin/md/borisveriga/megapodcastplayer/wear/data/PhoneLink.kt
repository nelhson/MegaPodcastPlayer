package md.borisveriga.megapodcastplayer.wear.data

/**
 * The state of the link to the phone.
 *
 * The watch is a remote control and nothing else, so "can I reach the thing I control" is the first
 * thing its UI has to answer, and the three ways of failing need three different sentences: nothing
 * the user can do about a missing app is the same as what they would do about a phone left upstairs.
 */
enum class PhoneLink {

    /** The first lookup has not come back yet. */
    CHECKING,

    /** A phone is in range and it has MegaPodcastPlayer installed. Commands will arrive. */
    CONNECTED,

    /** A phone is in range but is not advertising MegaPodcastPlayer's capability. */
    APP_NOT_INSTALLED,

    /** No phone is reachable — out of Bluetooth range, or Bluetooth is off. */
    DISCONNECTED,
}
