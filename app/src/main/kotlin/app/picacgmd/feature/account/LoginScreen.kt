package app.picacgmd.feature.account

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.picacgmd.core.data.settings.AppSettings
import app.picacgmd.core.designsystem.component.BackButton
import app.picacgmd.core.designsystem.component.PredictiveBackContent
import app.picacgmd.core.domain.session.SessionState
import app.picacgmd.core.domain.session.apiRouteState
import app.picacgmd.di.LocalAppContainer
import app.picacgmd.feature.routing.RoutePickerDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Login gate also owns the two-step registration and progressive password recovery flows. */
@Composable
fun LoginScreen(onBack: (() -> Unit)? = null) {
    val context = LocalContext.current
    val onboarding = remember(context) { context.getSharedPreferences("onboarding", Context.MODE_PRIVATE) }
    var page by rememberSaveable { mutableStateOf(if (onboarding.getBoolean("notice_accepted", false)) "login" else "notice") }
    var network by rememberSaveable { mutableStateOf(false) }
    PredictiveBackContent(
        current = page,
        previous = "login",
        onBack = { page = "login" },
        enabled = page !in setOf("login", "notice"),
    ) { current, back ->
        when (current) {
            "notice" -> NoticeScreen(onBack) {
                onboarding.edit().putBoolean("notice_accepted", true).apply()
                page = "login"
            }
            "register" -> RegisterScreen(onBack = back, onRegistered = { page = "login" })
            "forgot" -> ForgotPasswordScreen(onBack = back)
            else -> LoginForm(onBack, { page = "register" }, { page = "forgot" }, { network = true })
        }
    }
    if (network) RoutePickerDialog(onDismiss = { network = false })
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun NoticeScreen(onBack: (() -> Unit)?, onContinue: () -> Unit) {
    var adult by rememberSaveable { mutableStateOf(false) }
    AccountScaffold(onBack = onBack) {
        Icon(
            Icons.Outlined.AutoAwesome, null,
            Modifier.size(88.dp).clip(MaterialShapes.Sunny.toShape())
                .background(MaterialTheme.colorScheme.primaryContainer).padding(24.dp),
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        Text("欢迎使用 PicACG", style = MaterialTheme.typography.headlineLargeEmphasized)
        Text("使用须知", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Card {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("内容分级", style = MaterialTheme.typography.titleMedium)
                Text("可能含限制级内容，仅限 18 岁及以上用户")
                Text("独立客户端", style = MaterialTheme.typography.titleMedium)
                Text("与内容平台无隶属关系，账号和内容由平台提供")
                Text("本机隐私", style = MaterialTheme.typography.titleMedium)
                Text("不收集使用数据，记录只保存在本机")
            }
        }
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .toggleable(value = adult, role = Role.Checkbox, onValueChange = { adult = it }),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(adult, onCheckedChange = null)
            Text("我已年满 18 岁，并同意遵守当地法律法规", Modifier.weight(1f))
        }
        Button(onContinue, Modifier.fillMaxWidth(), enabled = adult) { Text("继续") }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LoginForm(onBack: (() -> Unit)?, onRegister: () -> Unit, onForgot: () -> Unit, onNetwork: () -> Unit) {
    val container = LocalAppContainer.current
    val settings by container.settingsRepository.settings.collectAsStateWithLifecycle(AppSettings.DEFAULT)
    val session by container.sessionManager.state.collectAsStateWithLifecycle()
    var account by rememberSaveable { mutableStateOf(settings.accountEmail) }
    var password by remember { mutableStateOf("") }
    var rememberCredentials by rememberSaveable { mutableStateOf(true) }
    var error by remember { mutableStateOf((session as? SessionState.Expired)?.message) }
    var submitting by remember { mutableStateOf(false) }
    val busy = submitting || session == SessionState.SigningIn
    val scope = rememberCoroutineScope()
    LaunchedEffect(settings.accountEmail) { if (account.isBlank()) account = settings.accountEmail }
    AccountScaffold(onBack = onBack) {
        Icon(
            Icons.Outlined.AutoAwesome, null,
            Modifier.size(72.dp).clip(MaterialShapes.Cookie9Sided.toShape())
                .background(MaterialTheme.colorScheme.primary).padding(20.dp),
            tint = MaterialTheme.colorScheme.onPrimary,
        )
        Text("登录账号", style = MaterialTheme.typography.headlineLargeEmphasized)
        Text("使用哔咔账号登录", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            account, { account = it; error = null }, Modifier.fillMaxWidth(), label = { Text("账号") },
            enabled = !busy, singleLine = true, isError = error != null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        )
        PasswordField(password, { password = it; error = null }, enabled = !busy, isError = error != null)
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(
                value = rememberCredentials,
                enabled = !busy,
                role = Role.Checkbox,
                onValueChange = { rememberCredentials = it },
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(rememberCredentials, onCheckedChange = null, enabled = !busy)
            Text("记住账号和密码（本机加密保存）", style = MaterialTheme.typography.bodyMedium)
        }
        error?.let {
            AccountError(it)
            TextButton(onNetwork) { Text("切换分流") }
        }
        Button(
            onClick = {
                submitting = true
                error = null
                scope.launch {
                    try {
                        container.sessionManager.login(account.trim(), password, rememberCredentials)
                        password = ""
                        onBack?.invoke()
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (exception: Exception) {
                        error = exception.localizedMessage ?: "登录失败"
                    } finally {
                        submitting = false
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy && account.isNotBlank() && password.isNotEmpty(),
        ) {
            if (busy) LoadingIndicator(Modifier.size(24.dp))
            Text(if (busy) "正在登录" else "登录")
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onRegister, enabled = !busy) { Text("注册账号") }
            TextButton(onForgot, enabled = !busy) { Text("忘记密码") }
        }
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    val route = apiRouteState(settings).selected
                    Text(
                        listOfNotNull(route.label, route.address).joinToString(" · "),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        if (settings.proxyUrl.isBlank()) "未设置代理" else "已启用代理，分流暂不生效",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                FilledTonalButton(onNetwork, enabled = !busy) { Text("更改") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AccountScaffold(title: String = "", onBack: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Scaffold(topBar = {
        if (title.isNotEmpty() || onBack != null) TopAppBar(
            title = { Text(title) },
            navigationIcon = {
                if (onBack != null) BackButton(onBack)
            },
        )
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp), content = content,
            )
        }
    }
}

@Composable
internal fun PasswordField(value: String, onChange: (String) -> Unit, label: String = "密码", enabled: Boolean = true, isError: Boolean = false) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value, onChange, Modifier.fillMaxWidth(), enabled = enabled, singleLine = true, isError = isError,
        label = { Text(label) },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton({ visible = !visible }) {
                Icon(if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (visible) "隐藏密码" else "显示密码")
            }
        },
    )
}

@Composable
internal fun AccountError(message: String) {
    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
}
