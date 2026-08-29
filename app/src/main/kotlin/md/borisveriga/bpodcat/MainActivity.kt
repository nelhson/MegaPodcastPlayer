package md.borisveriga.bpodcat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import md.borisveriga.bpodcat.core.designsystem.theme.BPodcatTheme
import md.borisveriga.bpodcat.ui.BPodcatApp

/**
 * The single activity hosting BPodcat's Compose UI.
 *
 * Draws edge to edge so the Fold 7's inner display is used fully; window insets are consumed by the
 * individual screens' scaffolds.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            BPodcatTheme {
                BPodcatApp()
            }
        }
    }
}
