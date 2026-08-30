package md.borisveriga.bpodcat.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Asks for `POST_NOTIFICATIONS` once per launch, if it has not been granted.
 *
 * The background refresh is the only feature that needs it, and it runs with no UI, so the request
 * has to be made while a screen exists. Asking on start-up rather than behind a rationale screen is
 * deliberate: the platform stops showing the dialog after two dismissals, so a user who does not
 * want it is asked at most twice, ever, and one who does gets the permission without hunting for a
 * setting.
 *
 * Does nothing when the permission is already granted, so the common case costs one comparison.
 */
@Composable
fun NotificationPermissionEffect() {
    val context = LocalContext.current
    val alreadyGranted = remember(context) {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    val launcher = rememberLauncherForActivityResult(
        // The answer needs no handling: the notifier re-checks the permission every time it posts,
        // so a denial simply means it never does.
        contract = ActivityResultContracts.RequestPermission(),
        onResult = {},
    )

    LaunchedEffect(alreadyGranted) {
        if (!alreadyGranted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
