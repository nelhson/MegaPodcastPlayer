package md.borisveriga.megapodcastplayer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import md.borisveriga.megapodcastplayer.core.data.playback.EpisodePlayer
import md.borisveriga.megapodcastplayer.core.data.repository.UiPreferencesRepository
import md.borisveriga.megapodcastplayer.core.model.AppearanceSettings

/**
 * How the app should draw itself, for the one composable that wraps everything else.
 *
 * The whole of the activity's own state. It exists because the theme is a stored preference now:
 * the palette, where it comes from, and whether the dark one is true black are all chosen on the
 * settings screen and applied here, above the navigation graph, where a single [MegaPodcastPlayerTheme]
 * covers every screen.
 *
 * It also answers the launcher's *Resume* shortcut, which is the activity's business rather than
 * any screen's: it arrives on the launch intent and has to be acted on before there is a
 * composition to act in.
 *
 * @property uiPreferences the stored appearance choices.
 * @property episodePlayer starts playback; see [resume].
 */
@HiltViewModel
class MainActivityViewModel @Inject constructor(
    uiPreferences: UiPreferencesRepository,
    private val episodePlayer: EpisodePlayer,
) : ViewModel() {

    /**
     * The appearance to draw with, or null until it has been read.
     *
     * Null is not "the defaults": it is "not known yet", and the activity holds the splash screen
     * up while it lasts. A tenth of a second of the wrong theme, on every cold start, is exactly
     * the kind of flicker a stored theme is supposed to remove — and the read is a DataStore read,
     * so the wait is over before the first frame would have been drawn anyway.
     */
    val appearance: StateFlow<AppearanceSettings?> = uiPreferences.observeAppearance()
        .map<AppearanceSettings, AppearanceSettings?> { it }
        .stateIn(
            scope = viewModelScope,
            // Eagerly, not lazily: the value is wanted before the composition exists, because it
            // is what decides whether the composition may start.
            started = SharingStarted.Eagerly,
            initialValue = null,
        )

    /**
     * Carries on with whatever was playing — what the launcher's *Resume* shortcut asks for.
     *
     * Suspending rather than launching into [viewModelScope], which is the unusual half and is
     * deliberate: the caller is the activity, and the answer is only of use to a window that is
     * still open. Run in the activity's own scope it is cancelled with the window and started
     * again by the next `readIntent`, which is exactly right — a rotation mid-resume repeats it
     * rather than delivering its answer to a destroyed activity.
     *
     * @return true if there was something to resume, and therefore something worth opening the
     *   player over.
     */
    suspend fun resume(): Boolean = episodePlayer.resume()
}
