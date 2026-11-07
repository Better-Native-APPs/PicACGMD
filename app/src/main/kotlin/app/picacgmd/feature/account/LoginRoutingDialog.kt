package app.picacgmd.feature.account

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.picacgmd.core.data.settings.RoutingMode
import app.picacgmd.core.domain.session.NetworkConfiguration
import app.picacgmd.core.network.ApiRoute
import app.picacgmd.core.network.PicaDns
import app.picacgmd.di.LocalAppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal fun RoutingMode.label(): String = when (this) {
    RoutingMode.NONE -> "不分流"
    RoutingMode.ROUTE_1 -> "分流 1"
    RoutingMode.ROUTE_2 -> "分流 2"
    RoutingMode.ROUTE_3 -> "分流 3"
    RoutingMode.CUSTOM -> "自定义 IP"
}

@Composable
internal fun LoginRoutingDialog(onDismiss: () -> Unit) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(RoutingMode.ROUTE_1) }
    var customIp by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }
    LaunchedEffect(container) {
        val settings = container.settingsRepository.settings.first()
        mode = settings.apiRoutingMode
        customIp = settings.customApiIp
        ready = true
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("API 分流") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("访问不了时可以更换分流。只连接内容平台，不从其他服务器获取节点。")
                RoutingMode.entries.forEach { option ->
                    Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { mode = option }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(mode == option, { mode = option }, enabled = !busy)
                        Text(option.label())
                    }
                }
                if (mode == RoutingMode.CUSTOM) OutlinedTextField(customIp, { customIp = it }, Modifier.fillMaxWidth(), label = { Text("自定义 IP 地址") }, enabled = !busy, singleLine = true)
                error?.let { AccountError(it) }
            }
        },
        confirmButton = {
            FilledTonalButton({
                busy = true
                error = null
                scope.launch {
                    try {
                        val settings = container.settingsRepository.settings.first()
                        if (mode == RoutingMode.CUSTOM) PicaDns(ApiRoute.Custom(customIp.trim()))
                        NetworkConfiguration.from(settings.copy(apiRoutingMode = mode, customApiIp = customIp.trim()))
                        container.settingsRepository.setApiRouting(mode, customIp.trim())
                        container.networkConfigProvider.awaitReady()
                        onDismiss()
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (exception: Exception) {
                        error = exception.localizedMessage ?: "分流地址无效"
                    } finally {
                        busy = false
                    }
                }
            }, enabled = ready && !busy) { Text("保存") }
        },
        dismissButton = { TextButton(onDismiss, enabled = !busy) { Text("取消") } },
    )
}
