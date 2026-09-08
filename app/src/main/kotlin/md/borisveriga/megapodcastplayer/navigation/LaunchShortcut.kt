package md.borisveriga.megapodcastplayer.navigation

import androidx.navigation.NavController

/**
 * The prefix every shortcut action shares.
 *
 * Namespaced by application id for the same reason the notification extras are: these actions name
 * intents this app sends to itself, and an unqualified name is one another app can collide with.
 */
private const val SHORTCUT_ACTION_PREFIX = "md.borisveriga.megapodcastplayer.action"

/**
 * A shortcut in the launcher's long-press menu, and what tapping it asks the app to do.
 *
 * The four are declared in `res/xml/shortcuts.xml`, which is where their labels, icons and order
 * live; this is the other half — the meaning of the action each of those intents carries.
 *
 * An action rather than an extra on the launch intent: a shortcut intent is written in XML, where
 * an action is one attribute the platform is certain to preserve, and it is also what an intent is
 * ordinarily identified by. `LaunchShortcutTest` reads the XML and checks that every action in it
 * is one of these, so the two files cannot drift apart silently.
 *
 * @property action the intent action `res/xml/shortcuts.xml` declares for this shortcut.
 */
enum class LaunchShortcut(val action: String) {

    /**
     * Carry on with whatever was playing.
     *
     * The only one of the four that is not a destination: it is a playback command, handled by the
     * activity before the graph is involved, and the player sheet then opens over wherever the app
     * happened to be. See `MainActivity`.
     */
    RESUME("$SHORTCUT_ACTION_PREFIX.RESUME"),

    /** Open the queue tab. */
    QUEUE("$SHORTCUT_ACTION_PREFIX.QUEUE"),

    /** Open the downloads tab. */
    DOWNLOADS("$SHORTCUT_ACTION_PREFIX.DOWNLOADS"),

    /** Open the add-a-show screen, empty and focused, as the library's add button does. */
    ADD_SHOW("$SHORTCUT_ACTION_PREFIX.ADD_SHOW"),
    ;

    companion object {

        /**
         * The shortcut an intent's action names, or null if it names none.
         *
         * Null is the ordinary answer, not an error: every launch that is not a shortcut — the
         * launcher icon, a notification, a shared link — arrives here with an action this does not
         * recognise.
         *
         * @param action the launching intent's action.
         */
        fun fromAction(action: String?): LaunchShortcut? =
            entries.firstOrNull { it.action == action }
    }
}

/**
 * Goes where a launcher shortcut asked to go.
 *
 * Navigation only. [LaunchShortcut.RESUME] deliberately moves nothing: the user asked to carry on
 * listening, not to be taken somewhere, and the player is a sheet that opens over whatever screen
 * is already there — including, when the app was already running, the one they were reading.
 *
 * A tab is reached exactly as tapping the tab reaches it, so a shortcut and a tap leave the same
 * back stack behind; the add screen is pushed, as the library's add button pushes it, so backing
 * out of it returns to the app rather than leaving it.
 *
 * @param shortcut the shortcut that was tapped.
 */
internal fun NavController.navigateToShortcut(shortcut: LaunchShortcut) {
    when (shortcut) {
        LaunchShortcut.RESUME -> Unit

        LaunchShortcut.QUEUE -> navigateToTopLevel(TopLevelDestination.QUEUE)

        LaunchShortcut.DOWNLOADS -> navigateToTopLevel(TopLevelDestination.DOWNLOADS)

        // launchSingleTop so a second tap while the add screen is already open does not stack a
        // second copy of it, the same rule every other intent-driven navigation here follows.
        LaunchShortcut.ADD_SHOW -> navigate(Route.Search()) { launchSingleTop = true }
    }
}
