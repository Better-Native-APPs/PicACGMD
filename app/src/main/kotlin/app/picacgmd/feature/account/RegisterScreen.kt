package app.picacgmd.feature.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.picacgmd.core.network.RegisterDto
import app.picacgmd.core.designsystem.component.PredictiveBackContent
import app.picacgmd.di.LocalAppContainer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun RegisterScreen(onBack: () -> Unit, onRegistered: () -> Unit) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    // Keep the step with the in-memory password; process recreation restarts the form safely.
    var step by remember { mutableStateOf(1) }
    var email by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var gender by rememberSaveable { mutableStateOf("m") }
    var birthday by rememberSaveable { mutableStateOf("") }
    var birthdayPicker by remember { mutableStateOf(false) }
    val questions = remember { mutableStateListOf("", "", "") }
    val answers = remember { mutableStateListOf("", "", "") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var success by rememberSaveable { mutableStateOf(false) }
    val back = { if (step == 2 && !success && !busy) step = 1 else onBack() }
    PredictiveBackContent(
        current = step,
        previous = 1,
        onBack = back,
        enabled = step == 2 && !busy && !success,
    ) { shownStep, stepBack ->
        AccountScaffold("注册账号", onBack = stepBack) {
            if (success) {
                Text("已注册", style = MaterialTheme.typography.headlineMedium)
                Text("请妥善保存安全问题和答案")
                Button(onRegistered, Modifier.fillMaxWidth()) { Text("返回登录") }
            } else {
                Text("第 $shownStep 步，共 2 步", style = MaterialTheme.typography.titleMedium)
                LinearWavyProgressIndicator(progress = { shownStep / 2f }, modifier = Modifier.fillMaxWidth(), waveSpeed = 0.dp)
                if (shownStep == 1) {
                    OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(), label = { Text("账号") }, singleLine = true)
                    PasswordField(password, { password = it })
                    Text("密码至少 8 位，包含大小写字母和数字", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("昵称") }, singleLine = true)
                    Text("性别", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                        listOf("男" to "m", "女" to "f", "其他" to "bot").forEach { (label, value) ->
                            FilterChip(gender == value, { gender = value }, label = { Text(label) })
                        }
                    }
                    OutlinedButton({ birthdayPicker = true }, Modifier.fillMaxWidth()) {
                        Text(if (birthday.isBlank()) "选择生日" else "生日：$birthday")
                    }
                    error?.let { AccountError(it) }
                    Button({
                        error = when {
                            email.isBlank() -> "请输入账号"
                            password.length < 8 || password.none(Char::isUpperCase) || password.none(Char::isLowerCase) || password.none(Char::isDigit) -> "密码至少 8 位，包含大小写字母和数字"
                            name.trim().length !in 2..50 -> "昵称需为 2–50 字"
                            birthday.isBlank() -> "请选择生日"
                            LocalDate.parse(birthday).isAfter(LocalDate.now().minusYears(18)) -> "注册须年满 18 岁"
                            else -> null
                        }
                        if (error == null) step = 2
                    }, Modifier.fillMaxWidth()) { Text("下一步") }
                } else {
                    Text("填写三组安全问题，注册后无法修改")
                    repeat(3) { index ->
                        OutlinedTextField(questions[index], { questions[index] = it }, Modifier.fillMaxWidth(), label = { Text("问题 ${index + 1}") }, enabled = !busy)
                        OutlinedTextField(answers[index], { answers[index] = it }, Modifier.fillMaxWidth(), label = { Text("回答 ${index + 1}") }, enabled = !busy)
                    }
                    error?.let { AccountError(it) }
                    Button(
                        onClick = {
                            error = null
                            busy = true
                            scope.launch {
                                try {
                                    container.networkConfigProvider.api().register(
                                        RegisterDto(
                                            email.trim(), password, name.trim(), birthday, gender,
                                            questions[0].trim(), questions[1].trim(), questions[2].trim(),
                                            answers[0].trim(), answers[1].trim(), answers[2].trim(),
                                        ),
                                    ).getOrThrow()
                                    container.settingsRepository.setAccountEmail(email.trim())
                                    password = ""
                                    answers.indices.forEach { answers[it] = "" }
                                    success = true
                                } catch (exception: CancellationException) {
                                    throw exception
                                } catch (exception: Exception) {
                                    error = exception.localizedMessage ?: "注册失败"
                                } finally {
                                    busy = false
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy && questions.all(String::isNotBlank) && answers.all(String::isNotBlank),
                    ) { Text(if (busy) "正在注册" else "注册") }
                    TextButton({ step = 1; error = null }, enabled = !busy) { Text("上一步") }
                }
            }
        }
    }
    if (birthdayPicker) {
        val picker = rememberDatePickerState(
            initialSelectedDateMillis = (birthday.takeIf(String::isNotBlank)?.let(LocalDate::parse)
                ?: LocalDate.now().minusYears(18)).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { birthdayPicker = false },
            confirmButton = { TextButton({
                picker.selectedDateMillis?.let { birthday = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString() }
                birthdayPicker = false
            }) { Text("确定") } },
            dismissButton = { TextButton({ birthdayPicker = false }) { Text("取消") } },
        ) { DatePicker(picker) }
    }
}
