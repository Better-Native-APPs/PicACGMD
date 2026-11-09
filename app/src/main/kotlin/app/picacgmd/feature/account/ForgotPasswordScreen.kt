package app.picacgmd.feature.account

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.picacgmd.di.LocalAppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun ForgotPasswordScreen(onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var email by rememberSaveable { mutableStateOf("") }
    var questions by remember { mutableStateOf<List<String>>(emptyList()) }
    var question by rememberSaveable { mutableStateOf(0) }
    var answer by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    AccountScaffold("找回密码", onBack) {
        OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(), label = { Text("账号") }, singleLine = true, enabled = questions.isEmpty() && !busy)
        if (questions.isEmpty()) Text("输入账号查看安全问题")
        if (questions.isNotEmpty() && newPassword == null) {
            Text("选择一个安全问题", style = MaterialTheme.typography.titleMedium)
            Card {
                questions.forEachIndexed { index, label ->
                    Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { question = index }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(question == index, { question = index }, enabled = !busy)
                        Text(label, Modifier.weight(1f))
                    }
                }
            }
            OutlinedTextField(answer, { answer = it }, Modifier.fillMaxWidth(), label = { Text("回答") }, enabled = !busy)
        }
        newPassword?.let { password ->
            Card {
                Column(Modifier.padding(20.dp)) {
                    Text("新密码已生成", style = MaterialTheme.typography.titleLarge)
                    Text(password, Modifier.padding(vertical = 16.dp), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.headlineSmall)
                    Text("已复制到剪贴板，登录后请修改密码")
                }
            }
        }
        error?.let { AccountError(it) }
        Button(
            onClick = {
                if (newPassword != null) onBack() else {
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            val api = container.networkConfigProvider.api()
                            if (questions.isEmpty()) {
                                val result = api.forgotPassword(email.trim()).getOrThrow()
                                questions = listOf(result.question1, result.question2, result.question3)
                            } else {
                                val password = api.resetPassword(email.trim(), question + 1, answer.trim()).getOrThrow().password
                                check(password.isNotEmpty()) { "找回密码失败：服务器未返回新密码" }
                                newPassword = password
                                answer = ""
                                val clip = ClipData.newPlainText("新密码", password)
                                if (Build.VERSION.SDK_INT >= 33) {
                                    clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
                                }
                                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
                            }
                        } catch (exception: CancellationException) {
                            throw exception
                        } catch (exception: Exception) {
                            error = exception.localizedMessage ?: "找回密码失败"
                        } finally {
                            busy = false
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy && email.isNotBlank() && (questions.isEmpty() || answer.isNotBlank() || newPassword != null),
        ) { Text(when { busy -> "正在处理"; newPassword != null -> "返回登录"; questions.isEmpty() -> "下一步"; else -> "找回密码" }) }
    }
}
