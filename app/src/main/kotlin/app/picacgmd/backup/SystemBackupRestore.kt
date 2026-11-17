package app.picacgmd.backup

import android.app.Application
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.picacgmd.core.designsystem.component.AppTextButton
import app.picacgmd.core.designsystem.window.secureDialogProperties

internal object SystemBackupRestore {
    fun onApplicationCreate(application: Application) {
        SystemBackupStorage(application).consumeRestore()
    }
}

/** An interrupted composition leaves the notice pending until the user acknowledges the lock reset. */
@Composable
internal fun RestoreLockNotice() {
    val context = LocalContext.current.applicationContext
    val storage = remember(context) { SystemBackupStorage(context) }
    var pending by remember(storage) { mutableStateOf(storage.lockNoticePending) }
    if (pending) {
        AlertDialog(
            properties = secureDialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            onDismissRequest = {},
            title = { Text("应用锁已关闭") },
            text = { Text("应用数据刚从系统备份恢复。应用锁不随备份迁移，可在「设置 › 隐私与安全」里重新开启。") },
            confirmButton = {
                AppTextButton(onClick = {
                    storage.acknowledgeLockNotice()
                    pending = false
                }) { Text("知道了") }
            },
        )
    }
}
