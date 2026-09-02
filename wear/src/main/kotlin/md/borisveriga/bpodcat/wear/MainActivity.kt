package md.borisveriga.bpodcat.wear

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
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
        RequestNotificationPermission()
        AppScaffold {
            WatchPlayerScreen(viewModel = viewModel)
        }
    }
}

/**
 * Asks once for the permission the watch-face chip needs.
 *
 * Asked here, on first open, rather than at the moment the chip would appear: by then the app is
 * usually closed, and a permission dialog cannot be raised from a background service. The watch's
 * `minSdk` is past the version that made this a runtime permission, so there is no older path.
 *
 * A refusal costs only the chip. Nothing else on the screen depends on it, so the result is not
 * recorded and the user is not asked again — the system stops showing the dialog after a decision,
 * and pestering someone about a convenience they declined is worse than going without.
 */
@Composable
private fun RequestNotificationPermission() {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { },
    )

    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED

        if (!granted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
