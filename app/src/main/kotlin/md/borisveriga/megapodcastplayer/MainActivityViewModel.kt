package md.borisveriga.megapodcastplayer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
 * @property uiPreferences the stored appearance choices.
 */
@HiltViewModel
class MainActivityViewModel @Inject constructor(
    uiPreferences: UiPreferencesRepository,
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
}
