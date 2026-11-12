package app.picacgmd.feature.account

import androidx.activity.compose.BackHandler
import app.picacgmd.core.designsystem.window.secureDialogProperties

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import app.picacgmd.core.designsystem.component.AppButton
import app.picacgmd.core.designsystem.component.AppOutlinedButton
import app.picacgmd.core.designsystem.component.AppTextButton
import app.picacgmd.core.network.PicaException
import app.picacgmd.core.network.RegisterDto
import app.picacgmd.core.designsystem.component.PredictiveBackContent
import app.picacgmd.di.LocalAppContainer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val accountPattern = Regex("""^(?!.*\.\.)(?!.*\.$)[\w][\w.]{0,29}$""")
private const val ACCOUNT_RULE_MESSAGE = "账号只能用字母、数字、下划线或点（1–30 位），不能使用邮箱"

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun RegisterScreen(onBack: () -> Unit, onRegistered: (String) -> Unit) {
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
    val back = {
        if (!busy) {
            if (step == 2 && !success) step = 1 else onBack()
        }
    }
    PredictiveBackContent(
        current = step,
        previous = 1,
        onBack = back,
        enabled = step == 2 && !busy && !success,
    ) { shownStep, stepBack ->
        AccountScaffold("注册账号", onBack = stepBack) {
            if (success) {
                Text("注册成功", style = MaterialTheme.typography.headlineMedium)
                Text("账号：${email.trim()}")
                Text("请返回登录，输入密码后即可使用")
                Text("请妥善保存安全问题和答案")
                error?.let { AccountError(it) }
                AppButton(onBack, Modifier.fillMaxWidth(), enabled = !busy) { Text("返回登录") }
            } else {
                Text("第 $shownStep 步，共 2 步", style = MaterialTheme.typography.titleMedium)
                LinearWavyProgressIndicator(progress = { shownStep / 2f }, modifier = Modifier.fillMaxWidth(), waveSpeed = 0.dp)
                if (shownStep == 1) {
                    OutlinedTextField(
                        email,
                        { email = it },
                        Modifier.fillMaxWidth(),
                        label = { Text("账号") },
                        supportingText = { Text(ACCOUNT_RULE_MESSAGE) },
                        singleLine = true,
                    )
                    PasswordField(password, { password = it })
                    Text("密码至少 8 位", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("昵称") }, singleLine = true)
                    Text("性别", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                        listOf("男" to "m", "女" to "f", "其他" to "bot").forEach { (label, value) ->
                            FilterChip(gender == value, { gender = value }, label = { Text(label) })
                        }
                    }
                    AppOutlinedButton({ birthdayPicker = true }, Modifier.fillMaxWidth()) {
                        Text(if (birthday.isBlank()) "选择生日" else "生日：$birthday")
                    }
                    error?.let { AccountError(it) }
                    AppButton({
                        error = when {
                            email.isBlank() -> "请输入账号"
                            !accountPattern.matches(email.trim()) -> ACCOUNT_RULE_MESSAGE
                            password.length < 8 -> "密码至少 8 位"
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
                    AppButton(
                        onClick = {
                            val registration = RegisterDto(
                                email = email.trim(),
                                password = password,
                                name = name.trim(),
                                birthday = birthday,
                                gender = gender,
                                question1 = questions[0].trim(),
                                question2 = questions[1].trim(),
                                question3 = questions[2].trim(),
                                answer1 = answers[0].trim(),
                                answer2 = answers[1].trim(),
                                answer3 = answers[2].trim(),
                            )
                            error = null
                            busy = true
                            scope.launch {
                                var registered = false
                                try {
                                    container.networkConfigProvider.api().register(registration).getOrThrow()
                                    registered = true
                                    password = ""
                                    answers.indices.forEach { answers[it] = "" }
                                    // Registration creates an account, not an authenticated session.
                                    container.sessionManager.logout()
                                    container.settingsRepository.setAccountEmail(registration.email)
                                } catch (exception: CancellationException) {
                                    throw exception
                                } catch (exception: Exception) {
                                    val message = exception.localizedMessage ?: "注册失败"
                                    val detail = (exception as? PicaException)?.detail?.toString().orEmpty()
                                    error = when {
                                        registered -> "账号已注册，请返回登录"
                                        message.contains("email is already exist", ignoreCase = true) -> "账号已存在，请直接登录或更换账号"
                                        message.contains("name is already exist", ignoreCase = true) -> "昵称已被使用，请换一个昵称"
                                        message.equals("validation error", ignoreCase = true) -> when {
                                            detail.contains("email is required", ignoreCase = true) -> "请输入账号"
                                            detail.contains("email with value", ignoreCase = true) ||
                                                detail.contains("required pattern", ignoreCase = true) -> ACCOUNT_RULE_MESSAGE
                                            detail.contains("password length", ignoreCase = true) -> "密码至少 8 位"
                                            detail.contains("name", ignoreCase = true) -> "昵称不符合要求（2–50 字）"
                                            else -> message
                                        }
                                        else -> message
                                    }
                                } finally {
                                    if (registered) {
                                        success = true
                                        onRegistered(registration.email)
                                    }
                                    busy = false
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy && questions.all(String::isNotBlank) && answers.all(String::isNotBlank),
                    ) { Text(if (busy) "正在注册" else "注册") }
                    AppTextButton({ step = 1; error = null }, enabled = !busy) { Text("上一步") }
                }
            }
        }
    }
    BackHandler(enabled = busy) { }
    if (birthdayPicker) {
        val picker = rememberDatePickerState(
            initialSelectedDateMillis = (birthday.takeIf(String::isNotBlank)?.let(LocalDate::parse)
                ?: LocalDate.now().minusYears(18)).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
        )
        DatePickerDialog(
            properties = secureDialogProperties(usePlatformDefaultWidth = false),
            onDismissRequest = { birthdayPicker = false },
            confirmButton = { AppTextButton({
                picker.selectedDateMillis?.let { birthday = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString() }
                birthdayPicker = false
            }) { Text("确定") } },
            dismissButton = { AppTextButton({ birthdayPicker = false }) { Text("取消") } },
        ) { DatePicker(picker) }
    }
}
