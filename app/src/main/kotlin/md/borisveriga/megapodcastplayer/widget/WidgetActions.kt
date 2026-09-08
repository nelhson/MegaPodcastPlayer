package md.borisveriga.megapodcastplayer.widget

import android.content.Context
import android.content.Intent
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import md.borisveriga.megapodcastplayer.MainActivity
import md.borisveriga.megapodcastplayer.core.media.EXTRA_OPEN_PLAYER

/**
 * The action callbacks behind the widget's buttons.
 *
 * Each one is a class the framework instantiates by name, given a `Context` and the parameters the
 * button attached — so none of them may hold state, and all of them reach the app through
 * [widgetEntryPoint]. They run on Glance's own coroutine scope, which outlives the broadcast that
 * started them; that matters because binding a `MediaController` is not instant and a button that
 * returned before the command landed would be a button that did nothing.
 *
 * The widget does not redraw itself afterwards. Every one of these changes playback, the widget's
 * composition is collecting playback, and Glance pushes the new tree when the flow emits. A manual
 * `update` here would be a second, earlier redraw of a state that had not happened yet.
 */

/**
 * Play, or pause what is playing.
 *
 * The widget's primary button when an episode is loaded. When nothing is loaded the button is
 * [ResumeAction] instead, because there is nothing to toggle.
 */
internal class TogglePlayPauseAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        context.widgetEntryPoint().playbackConnection().togglePlayPause()
    }
}

/**
 * Carry on with whatever the app would carry on with.
 *
 * The same call the launcher's *Resume* shortcut makes, for the reason that shortcut's own KDoc
 * gives: resolving "the last episode" a second way would be a second answer to a question
 * `resumableQueue` already answers, and the two would disagree the first time one of them changed.
 *
 * Nothing to resume does nothing at all, which cannot happen from the widget — the button is only
 * drawn when the snapshot found something to draw it for.
 */
internal class ResumeAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        context.widgetEntryPoint().episodePlayer().resume()
    }
}

/** Back by the user's skip interval. */
internal class SkipBackAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        context.widgetEntryPoint().playbackConnection().skipBack()
    }
}

/** Ahead by the user's skip interval. */
internal class SkipForwardAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        context.widgetEntryPoint().playbackConnection().skipForward()
    }
}

/**
 * Play one episode from the shelf.
 *
 * The same `EpisodePlayer.play` a card on the Listen screen calls, so an episode started from the
 * home screen resumes at its stored position and skips the show's intro on the same terms.
 *
 * The episode is carried as an action parameter rather than looked up here: the widget already
 * knows which cover was pressed, and re-deriving it from a position in a list would be wrong the
 * moment the shelf reordered between the draw and the press.
 */
internal class PlayEpisodeAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val episodeId = parameters[episodeIdKey] ?: return
        context.widgetEntryPoint().episodePlayer().play(episodeId)
    }
}

/** Which episode [PlayEpisodeAction] should start. */
internal val episodeIdKey = ActionParameters.Key<String>("episodeId")

/**
 * The intent behind a tap on the widget's body: open the app, with the player already up.
 *
 * [EXTRA_OPEN_PLAYER] is the media notification's own extra, reused rather than reinvented — both
 * are a tap on a picture of what is playing, and both should land on the player rather than on
 * whichever tab the app was last left on.
 *
 * @param context any context; the intent names this app's activity explicitly.
 * @return the intent to give an `actionStartActivity`.
 */
internal fun openPlayerIntent(context: Context): Intent =
    Intent(context, MainActivity::class.java)
        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        .putExtra(EXTRA_OPEN_PLAYER, true)

/**
 * The intent behind a tap on the widget when there is nothing to play: open the app.
 *
 * Not the player. Expanding an empty sheet answers "I want to listen to something" with a blank
 * screen, which is the same reasoning the *Resume* shortcut follows when it finds nothing to
 * resume.
 *
 * @param context any context.
 * @return the intent to give an `actionStartActivity`.
 */
internal fun openAppIntent(context: Context): Intent =
    Intent(context, MainActivity::class.java)
        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
