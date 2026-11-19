package app.picacgmd.feature.account

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import app.picacgmd.core.data.settings.AppSettings
import app.picacgmd.core.data.settings.SettingsRepository
import app.picacgmd.core.designsystem.theme.DarkMode
import app.picacgmd.core.designsystem.theme.PicaTheme
import app.picacgmd.core.designsystem.theme.ThemeChoice
import app.picacgmd.core.domain.di.AppContainer
import app.picacgmd.core.domain.session.NetworkClients
import app.picacgmd.core.domain.session.NetworkConfigProvider
import app.picacgmd.core.domain.session.SessionManager
import app.picacgmd.core.network.PicaApi
import app.picacgmd.di.LocalAppContainer
import app.picacgmd.testing.ComposeHostRule
import app.picacgmd.testing.MemoryCredentialStore
import app.picacgmd.testing.MemoryPreferencesStore
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sun.misc.Unsafe
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w360dp-h1800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AuthFormRestorationTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(ComposeHostRule()).around(composeRule)

    private val fixture = AuthFormTestFixture()

    @After
    fun closeFixture() = fixture.close()

    @Test
    fun loginRestoresTheAccountCursorAndRememberChoiceButNeverThePassword() {
        val restoration = showLogin()
        field("账号").performTextReplacement("draft.account")
        field("密码").performTextInput("login-secret")
        field("账号").performTextInputSelection(TextRange(2, 5))
        composeRule.onNodeWithText("记住账号和密码").performClick()
        button("登录").assertIsEnabled()

        restoration.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText("登录账号").assertIsDisplayed()
        assertField("账号", "draft.account", TextRange(2, 5))
        assertField("密码", "")
        composeRule.onNodeWithText("记住账号和密码").assertIsOff()
        button("登录").assertIsNotEnabled()
        assertNoFieldErrors()
        assertEquals(SAVED_ACCOUNT, runBlocking { fixture.settings.settings.first() }.accountEmail)

        field("账号").performTextClearance()
        restoration.emulateSavedInstanceStateRestore()

        assertField("账号", "")
        assertField("密码", "")
        assertNoFieldErrors()
    }

    @Test
    fun registrationRestoresNonSecretsAtTheFirstStepRequiringAClearedSecret() {
        val restoration = showLogin()
        button("注册账号").performScrollTo().performClick()
        field("账号").performTextInput("draft.reader")
        field("密码").performTextInput("register-secret")
        field("昵称").performTextInput("测试昵称")
        composeRule.onNodeWithText("其他").performClick()
        button("选择生日").performClick()
        button("确定").performClick()
        val birthday = LocalDate.now().minusYears(18).toString()
        field("账号").performTextInputSelection(TextRange(3))

        restoration.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText("第 1 步，共 2 步").assertIsDisplayed()
        assertField("账号", "draft.reader", TextRange(3))
        assertField("昵称", "测试昵称")
        assertField("密码", "")
        composeRule.onNodeWithText("其他").assertIsSelected()
        button("生日：$birthday").assertExists()
        assertNoFieldErrors()

        field("密码").performTextInput("register-secret")
        button("下一步").performScrollTo().performClick()
        repeat(3) { index ->
            field("问题 ${index + 1}").performScrollTo().performTextInput("安全问题 ${index + 1}")
            field("回答 ${index + 1}").performScrollTo().performTextInput("answer-secret-${index + 1}")
        }
        field("问题 2").performTextInputSelection(TextRange(2))
        button("注册").assertIsEnabled()

        restoration.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText("第 1 步，共 2 步").assertExists()
        assertField("账号", "draft.reader")
        assertField("昵称", "测试昵称")
        assertField("密码", "")
        composeRule.onNodeWithText("其他").assertIsSelected()
        button("生日：$birthday").assertExists()
        assertNoFieldErrors()
        field("密码").performTextInput("new-register-secret")
        button("下一步").performScrollTo().performClick()

        composeRule.onNodeWithText("第 2 步，共 2 步").assertExists()
        repeat(3) { index ->
            assertField("问题 ${index + 1}", "安全问题 ${index + 1}")
            assertField("回答 ${index + 1}", "")
        }
        assertField("问题 2", "安全问题 2", TextRange(2))
        button("注册").assertIsNotEnabled()
        assertNoFieldErrors()

        repeat(3) { index ->
            field("回答 ${index + 1}").performScrollTo().performTextInput("new-answer-${index + 1}")
        }
        button("注册").assertIsEnabled()
        assertEquals(emptyList(), fixture.requests.toList())
    }

    @Test
    fun passwordRecoveryRestoresAccountQuestionsAndSelectionWithoutAnswersOrGeneratedPasswords() {
        val restoration = showLogin()
        button("忘记密码").performScrollTo().performClick()
        field("账号").performTextInput("recover.reader")
        field("账号").performTextInputSelection(TextRange(4))
        button("下一步").performClick()
        composeRule.waitUntil { composeRule.onAllNodes(hasText(RECOVERY_QUESTIONS[2])).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText(RECOVERY_QUESTIONS[2]).performClick()
        field("回答").performTextInput("recovery-answer-secret")
        button("找回密码").assertIsEnabled()

        restoration.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText("选择一个安全问题").assertIsDisplayed()
        assertField("账号", "recover.reader", TextRange(4))
        RECOVERY_QUESTIONS.forEach { composeRule.onNodeWithText(it).assertIsDisplayed() }
        composeRule.onNodeWithText(RECOVERY_QUESTIONS[2]).assertIsSelected()
        assertField("回答", "")
        button("找回密码").assertIsNotEnabled()
        assertNoFieldErrors()
        assertEquals(listOf("/auth/forgot-password"), fixture.requests.toList())

        field("回答").performTextInput("new-recovery-answer")
        button("找回密码").performClick()
        composeRule.waitUntil { composeRule.onAllNodes(hasText(GENERATED_PASSWORD)).fetchSemanticsNodes().isNotEmpty() }
        restoration.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText("新密码已生成").assertDoesNotExist()
        composeRule.onNodeWithText(GENERATED_PASSWORD).assertDoesNotExist()
        composeRule.onNodeWithText(RECOVERY_QUESTIONS[2]).assertIsSelected()
        assertField("账号", "recover.reader")
        assertField("回答", "")
        button("找回密码").assertIsNotEnabled()
        assertNoFieldErrors()
        assertEquals(listOf("/auth/forgot-password", "/auth/reset-password"), fixture.requests.toList())
    }

    private fun showLogin(): StateRestorationTester {
        RuntimeEnvironment.getApplication().getSharedPreferences("onboarding", Context.MODE_PRIVATE)
            .edit().putBoolean("notice_accepted", true).commit()
        return StateRestorationTester(composeRule).also { restoration ->
            restoration.setContent {
                CompositionLocalProvider(LocalAppContainer provides fixture.container) {
                    PicaTheme(themeChoice = ThemeChoice.Brand, darkMode = DarkMode.Light) {
                        LoginScreen()
                    }
                }
            }
            composeRule.waitForIdle()
        }
    }

    private fun field(label: String): SemanticsNodeInteraction =
        composeRule.onNode(hasText(label) and SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText))

    private fun button(label: String): SemanticsNodeInteraction =
        composeRule.onNode(hasText(label) and hasClickAction())

    private fun assertField(label: String, text: String, selection: TextRange? = null) {
        field(label).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(text)))
        if (selection != null) {
            field(label).assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, selection))
        }
    }

    private fun assertNoFieldErrors() {
        composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error)).assertCountEquals(0)
        composeRule.onNodeWithText("请输入账号").assertDoesNotExist()
        composeRule.onNodeWithText("请选择生日").assertDoesNotExist()
    }
}

private const val SAVED_ACCOUNT = "saved.reader"
private const val GENERATED_PASSWORD = "generated-secret"
private val RECOVERY_QUESTIONS = listOf("你的第一本漫画？", "你最喜欢的颜色？", "你的出生城市？")

private class AuthFormTestFixture : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    val settings = SettingsRepository(MemoryPreferencesStore(), AppSettings(accountEmail = SAVED_ACCOUNT, autoPunchIn = false))
    val requests = CopyOnWriteArrayList<String>()
    private val client = OkHttpClient.Builder().addInterceptor { chain ->
        val path = chain.request().url.encodedPath
        requests += path
        val data = when (path) {
            "/auth/forgot-password" -> """{"question1":"${RECOVERY_QUESTIONS[0]}","question2":"${RECOVERY_QUESTIONS[1]}","question3":"${RECOVERY_QUESTIONS[2]}"}"""
            "/auth/reset-password" -> """{"password":"$GENERATED_PASSWORD"}"""
            else -> error("Unexpected auth form request: $path")
        }
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body("""{"code":200,"message":"success","data":$data}""".toResponseBody("application/json".toMediaType()))
            .build()
    }.build()
    private val api = PicaApi(baseUrl = "https://auth.test/", client = client)
    private val session = SessionManager(settings, MemoryCredentialStore(), { api }, scope)
    private val network = NetworkConfigProvider(settings.settings, scope).also { network ->
        val original = network.current
        network.close()
        @Suppress("UNCHECKED_CAST")
        val clients = NetworkConfigProvider::class.java.getDeclaredField("mutableClients").apply { isAccessible = true }
            .get(network) as MutableStateFlow<NetworkClients>
        clients.value = original.copy(apiClient = client, imageClient = client, api = api)
    }
    val container: AppContainer = run {
        // Populate only auth dependencies, without Android startup or background work.
        val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as Unsafe
        (unsafe.allocateInstance(AppContainer::class.java) as AppContainer).also { container ->
            mapOf("settingsRepository" to settings, "sessionManager" to session, "networkConfigProvider" to network)
                .forEach { (name, value) ->
                    AppContainer::class.java.getDeclaredField(name).apply { isAccessible = true }.set(container, value)
                }
        }
    }

    override fun close() {
        scope.cancel()
        network.close()
    }
}
