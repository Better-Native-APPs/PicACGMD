package app.picacgmd.feature.games

import app.picacgmd.core.designsystem.window.secureDialogProperties
import app.picacgmd.core.designsystem.window.secureModalBottomSheetProperties

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.picacgmd.core.designsystem.component.AppButton
import app.picacgmd.core.designsystem.component.AppFilledTonalButton
import app.picacgmd.core.designsystem.component.AppOutlinedButton
import app.picacgmd.core.designsystem.component.AppTextButton
import kotlinx.coroutines.launch

/** API-supplied addresses are displayed locally; no third-party requests or redirects are prefetched. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun GameLinksSheet(links: List<String>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val clipboard = remember(context) { context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var opening by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, properties = secureModalBottomSheetProperties()) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("下载链接", style = MaterialTheme.typography.headlineSmall)
            links.forEach { raw ->
                // Preserve codes/passwords alongside the original address instead of silently dropping them.
                val urls = remember(raw) { Regex("https?://[^\\s<>\"']+").findAll(raw).map { it.value }.filter { Uri.parse(it).host != null }.toList() }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SelectionContainer { Text(raw) }
                        AppFilledTonalButton({
                            clipboard.setPrimaryClip(ClipData.newPlainText("下载链接", raw))
                            scope.launch { snackbar.showSnackbar("已复制") }
                        }) { Text("复制") }
                        urls.forEach { url -> AppOutlinedButton({ opening = url }, Modifier.fillMaxWidth()) { Text("打开 ${Uri.parse(url).host}") } }
                    }
                }
            }
            Text("外部网站可记录 IP 地址，文件未经验证", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SnackbarHost(snackbar)
        }
    }
    opening?.let { url ->
        AlertDialog(
            properties = secureDialogProperties(),
            onDismissRequest = { opening = null },
            title = { Text("用浏览器打开？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(Uri.parse(url).host.orEmpty(), style = MaterialTheme.typography.titleSmallEmphasized, color = MaterialTheme.colorScheme.primary)
                    SelectionContainer { Text(url) }
                    Text("将离开本应用：网站不属于哔咔，可记录 IP 地址，文件未经验证")
                }
            },
            confirmButton = { AppButton({
                opening = null
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
                } catch (_: ActivityNotFoundException) {
                    scope.launch { snackbar.showSnackbar("打开失败：无可用浏览器") }
                }
            }) { Text("打开") } },
            dismissButton = { AppTextButton({ opening = null }) { Text("取消") } },
        )
    }
}
