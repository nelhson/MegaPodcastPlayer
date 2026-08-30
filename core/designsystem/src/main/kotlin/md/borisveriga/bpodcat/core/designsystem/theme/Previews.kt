package md.borisveriga.bpodcat.core.designsystem.theme

import android.content.res.Configuration.UI_MODE_NIGHT_NO
import android.content.res.Configuration.UI_MODE_NIGHT_YES
import androidx.compose.ui.tooling.preview.Preview

/**
 * Multipreview annotations.
 *
 * Previously each screen carried a single bare `@Preview` with no background and no dark
 * variant, which is why a palette that only ever rendered in previews still went unnoticed.
 * Annotating with [ThemePreviews] renders both schemes side by side; [FontScalePreviews] catches
 * the layouts that break when a user turns text size up, which is most of them until checked.
 */

/** Light and dark, both with a background so contrast is actually visible in the preview pane. */
@Preview(name = "Light", showBackground = true, uiMode = UI_MODE_NIGHT_NO)
@Preview(name = "Dark", showBackground = true, uiMode = UI_MODE_NIGHT_YES)
annotation class ThemePreviews

/**
 * Default, large and largest text.
 *
 * 2.0 is not hypothetical — it is reachable from Android's own display settings, and it is where
 * fixed-height rows and single-line titles fall apart.
 */
@Preview(name = "Font 100%", showBackground = true, fontScale = 1.0f)
@Preview(name = "Font 150%", showBackground = true, fontScale = 1.5f)
@Preview(name = "Font 200%", showBackground = true, fontScale = 2.0f)
annotation class FontScalePreviews
