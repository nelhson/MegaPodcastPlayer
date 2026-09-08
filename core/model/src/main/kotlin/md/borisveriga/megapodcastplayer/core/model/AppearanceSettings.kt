package md.borisveriga.megapodcastplayer.core.model

/**
 * Which palette the app draws itself in.
 *
 * Three, because "follow the system" is a real answer and not the absence of one: a phone that
 * switches to dark at sunset should take the app with it, and that is what most people want. The
 * other two exist for the people it is wrong for — a light app in a dark system, or the reverse —
 * and there is no way to infer which of those a user is.
 */
enum class ThemeChoice {

    /** Whatever the phone is doing. The default. */
    SYSTEM,

    /** Always light, whatever the phone is doing. */
    LIGHT,

    /** Always dark, likewise. */
    DARK,
}

/**
 * How the app looks, as the user has asked for it.
 *
 * Separate from `PlaybackSettings` and `DownloadSettings` because nothing here changes what the app
 * *does*. It is the same distinction `UiPreferencesRepository` exists for, and these live beside
 * the library layout for that reason.
 *
 * @property theme which palette to draw in.
 * @property dynamicColor whether to take the palette from the wallpaper instead of the app's own.
 *   Off by default, and that is the considered answer rather than an oversight: this app has a
 *   brand, and it used to be invisible because a `dynamicColor` parameter defaulting to true meant
 *   the wallpaper branch was taken on every device with `minSdk 34`. It is here because *some*
 *   people want their phone to match itself, and it costs nothing to let them.
 * @property pureBlack whether the dark theme's backgrounds are true black rather than very dark
 *   grey. Only meaningful while the app is dark, and only really meaningful on an OLED panel —
 *   which is what the Fold 7 has, and where a black pixel is a pixel that is off.
 */
data class AppearanceSettings(
    val theme: ThemeChoice = ThemeChoice.SYSTEM,
    val dynamicColor: Boolean = false,
    val pureBlack: Boolean = false,
) {
    /**
     * Whether the app should be dark right now.
     *
     * @param systemInDarkTheme what the phone is currently doing, for [ThemeChoice.SYSTEM].
     * @return true when the dark palette applies.
     */
    fun isDark(systemInDarkTheme: Boolean): Boolean = when (theme) {
        ThemeChoice.SYSTEM -> systemInDarkTheme
        ThemeChoice.LIGHT -> false
        ThemeChoice.DARK -> true
    }

    /**
     * Whether the pure-black option has any effect right now.
     *
     * A light theme drawn on black would be unreadable, so the option is ignored rather than
     * applied there; the screen that offers it uses this to say so.
     *
     * @param systemInDarkTheme what the phone is currently doing.
     * @return true when [pureBlack] is on and the app is dark.
     */
    fun isPureBlack(systemInDarkTheme: Boolean): Boolean = pureBlack && isDark(systemInDarkTheme)

    companion object {
        /** What the app looks like until the user says otherwise. */
        val DEFAULT = AppearanceSettings()
    }
}
