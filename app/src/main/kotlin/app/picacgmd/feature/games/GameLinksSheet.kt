package app.picacgmd.feature.games

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
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** API-supplied addresses are displayed locally; no third-party requests or redirects are prefetched. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GameLinksSheet(links: List<String>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val clipboard = remember(context) { context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var opening by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("下载链接", style = MaterialTheme.typography.headlineSmall)
            Text("以下内容来自游戏资料。复制不会联网；打开链接会离开本应用。", style = MaterialTheme.typography.bodyMedium)
            links.forEach { raw ->
                // Preserve codes/passwords alongside the original address instead of silently dropping them.
                val urls = remember(raw) { Regex("https?://[^\\s<>\"']+").findAll(raw).map { it.value }.filter { Uri.parse(it).host != null }.toList() }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SelectionContainer { Text(raw) }
                        FilledTonalButton({
                            clipboard.setPrimaryClip(ClipData.newPlainText("下载链接", raw))
                            scope.launch { snackbar.showSnackbar("已复制") }
                        }) { Text("复制") }
                        urls.forEach { url -> OutlinedButton({ opening = url }, Modifier.fillMaxWidth()) { Text("打开 ${Uri.parse(url).host}") } }
                    }
                }
            }
            Text("外部网站可记录你的 IP 地址，提供的文件未经验证，请自行判断。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SnackbarHost(snackbar)
        }
    }
    opening?.let { url ->
        AlertDialog(
            onDismissRequest = { opening = null },
            title = { Text("用浏览器打开？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(Uri.parse(url).host.orEmpty(), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    SelectionContainer { Text(url) }
                    Text("这个网站不属于哔咔。继续后它可以记录你的 IP 地址。")
                    Text("即将离开本应用。外部文件没有经过检查，请自行判断。")
                }
            },
            confirmButton = { FilledTonalButton({
                opening = null
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
                } catch (_: ActivityNotFoundException) {
                    scope.launch { snackbar.showSnackbar("没有可用的浏览器，请复制链接后手动打开") }
                }
            }) { Text("打开") } },
            dismissButton = { TextButton({ opening = null }) { Text("取消") } },
        )
    }
}
