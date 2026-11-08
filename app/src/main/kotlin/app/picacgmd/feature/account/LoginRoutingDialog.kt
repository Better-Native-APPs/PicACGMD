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
import app.picacgmd.core.data.settings.AppSettings
import app.picacgmd.core.domain.session.apiRouteState
import app.picacgmd.core.domain.session.RouteSource
import app.picacgmd.core.network.ApiRoute
import app.picacgmd.di.LocalAppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
internal fun LoginRoutingDialog(onDismiss: () -> Unit) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    var route by remember { mutableStateOf<ApiRoute>(ApiRoute.None) }
    var custom by remember { mutableStateOf(false) }
    var routes by remember { mutableStateOf(apiRouteState(AppSettings.DEFAULT)) }
    var customIp by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }
    LaunchedEffect(container) {
        val settings = container.settingsRepository.settings.first()
        routes = apiRouteState(settings)
        route = routes.selected.value
        custom = routes.selected.source == RouteSource.CUSTOM
        customIp = settings.customApiIp
        ready = true
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("API 分流") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("访问不了时可以更换分流。分流地址由哔咔提供。")
                routes.options.filter { it.source != RouteSource.CUSTOM }.forEach { option ->
                    val selected = !custom && route == option.value
                    val select = { route = option.value; custom = false }
                    Row(Modifier.fillMaxWidth().clickable(enabled = !busy, onClick = select), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected, select, enabled = !busy)
                        Text(option.label)
                    }
                }
                Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { custom = true }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(custom, { custom = true }, enabled = !busy)
                    Text("自定义")
                }
                if (custom) OutlinedTextField(customIp, { customIp = it }, Modifier.fillMaxWidth(), label = { Text("自定义 IP 地址") }, enabled = !busy, singleLine = true)
                error?.let { AccountError(it) }
            }
        },
        confirmButton = {
            FilledTonalButton({
                busy = true
                error = null
                scope.launch {
                    try {
                        if (custom) container.routingRepository.setCustomApiRoute(customIp)
                        else container.routingRepository.selectApiRoute(route)
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
