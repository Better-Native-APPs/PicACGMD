package app.picacgmd.feature.reader.media

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import app.picacgmd.feature.reader.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch

/** Requests legacy gallery access only when saving, using the reader's existing failure snackbar. */
@Composable
internal fun rememberGallerySavePermission(snackbar: SnackbarHostState): suspend () -> Boolean {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val deniedMessage = stringResource(R.string.reader_storage_permission_required)
    val pending = remember { mutableStateOf<CompletableDeferred<Boolean>?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        pending.value?.complete(granted)
    }
    return remember(context, scope, snackbar, deniedMessage, launcher) {
        suspend {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                true
            } else {
                val result = pending.value ?: CompletableDeferred<Boolean>().also {
                    pending.value = it
                    launcher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
                val granted = try {
                    result.await()
                } finally {
                    if (pending.value === result) pending.value = null
                }
                if (!granted) scope.launch { snackbar.showSnackbar(deniedMessage) }
                granted
            }
        }
    }
}
