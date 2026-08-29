package md.borisveriga.bpodcat.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.MaterialTheme
import dagger.hilt.android.AndroidEntryPoint
import md.borisveriga.bpodcat.wear.ui.WatchPlayerScreen
import md.borisveriga.bpodcat.wear.ui.WatchPlayerViewModel

/**
 * The watch app's single activity.
 *
 * Uses Wear Compose Material 3 (Material 3 Expressive); the mobile Material 3 library must never be
 * mixed in here.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WearApp()
        }
    }
}

/**
 * Root composable of the watch app.
 *
 * There is only one screen: the app is a remote control for the phone's player, and everything it
 * can do fits in one scrolling list. [AppScaffold] is still used, for the time text along the top
 * bezel that every Wear app is expected to show.
 */
@Composable
private fun WearApp(viewModel: WatchPlayerViewModel = hiltViewModel()) {
    MaterialTheme {
        AppScaffold {
            WatchPlayerScreen(viewModel = viewModel)
        }
    }
}
