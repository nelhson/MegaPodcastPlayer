package md.borisveriga.bpodcat.core.designsystem.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Fallback palette used when dynamic color is unavailable or disabled.
 *
 * The accent is a warm amber, chosen to stay legible over the podcast artwork that dominates most
 * screens.
 */
private val LightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF8A5100),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDCBE),
    onPrimaryContainer = Color(0xFF2C1600),
    secondary = Color(0xFF725A42),
    onSecondary = Color(0xFFFFFFFF),
    surface = Color(0xFFFFF8F5),
    onSurface = Color(0xFF201B16),
)

private val DarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFFFFB868),
    onPrimary = Color(0xFF4A2800),
    primaryContainer = Color(0xFF693C00),
    onPrimaryContainer = Color(0xFFFFDCBE),
    secondary = Color(0xFFE1C1A4),
    onSecondary = Color(0xFF402C18),
    surface = Color(0xFF18120C),
    onSurface = Color(0xFFECE0D9),
)

/**
 * Applies BPodcat's Material 3 theme.
 *
 * @param darkTheme whether to use the dark colour scheme; follows the system setting by default.
 * @param dynamicColor whether to derive colours from the device wallpaper (Android 12+). Enabled by
 *   default because the app targets a single personal device where Material You looks best.
 * @param content the themed content.
 */
@Composable
fun BPodcatTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
