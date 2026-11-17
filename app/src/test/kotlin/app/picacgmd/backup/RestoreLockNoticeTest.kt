package app.picacgmd.backup

import android.os.SystemClock
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentDialog
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.picacgmd.core.designsystem.theme.DarkMode
import app.picacgmd.core.designsystem.theme.PicaTheme
import app.picacgmd.core.designsystem.theme.ThemeChoice
import app.picacgmd.core.designsystem.window.LocalSecureWindows
import app.picacgmd.testing.ComposeHostRule
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RestoreLockNoticeTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(ComposeHostRule()).around(composeRule)

    private lateinit var storage: SystemBackupStorage
    private var noticeMounted by mutableStateOf(true)

    @Before
    fun setUp() {
        BackupTestKeyStore.install()
        val context = RuntimeEnvironment.getApplication()
        storage = SystemBackupStorage(context)
        storage.settingsFile.parentFile?.deleteRecursively()
        context.noBackupFilesDir.listFiles()?.forEach { it.deleteRecursively() }
        storage.clearSnapshots()
        // Seed a committed restore input; this notice fixture is not a platform restore session.
        populateBackupSettings(storage.restoredSettingsSnapshot)
        storage.markRestorePending()
        storage.consumeRestore()
    }

    @After
    fun tearDown() {
        BackupTestKeyStore.remove()
    }

    @Test
    fun pendingNoticeShowsTheFixedCopyInASecureComposeDialog() {
        host()
        composeRule.onNodeWithText("应用锁已关闭").assertIsDisplayed()
        composeRule.onNodeWithText(
            "应用数据刚从系统备份恢复。应用锁不随备份迁移，可在「设置 › 隐私与安全」里重新开启。",
        ).assertIsDisplayed()
        composeRule.onNodeWithText("知道了").assertIsDisplayed()
        composeRule.runOnIdle {
            val dialog = assertNotNull(ShadowDialog.getLatestDialog())
            assertTrue(dialog.isShowing)
            assertTrue(assertNotNull(dialog.window).attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            assertTrue(storage.lockNoticePending)
        }
    }

    @Test
    fun noticeIsAbsentWhenNotPending() {
        storage.acknowledgeLockNotice()
        host()
        composeRule.onNodeWithText("应用锁已关闭").assertDoesNotExist()
        composeRule.onNodeWithText("知道了").assertDoesNotExist()
        composeRule.runOnIdle { assertFalse(storage.lockNoticePending) }
    }

    @Test
    fun backAndOutsideTapDoNotDismissOrAcknowledgeTheNotice() {
        host()
        composeRule.runOnIdle {
            val dialog = assertNotNull(ShadowDialog.getLatestDialog()) as ComponentDialog
            dialog.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.onNodeWithText("应用锁已关闭").assertIsDisplayed()
        composeRule.runOnIdle {
            assertTrue(storage.lockNoticePending)
            val dialog = assertNotNull(ShadowDialog.getLatestDialog())
            val time = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(time, time, action, -100f, -100f, 0)
                try {
                    dialog.dispatchTouchEvent(event)
                } finally {
                    event.recycle()
                }
            }
        }
        composeRule.onNodeWithText("应用锁已关闭").assertIsDisplayed()
        composeRule.runOnIdle {
            assertTrue(assertNotNull(ShadowDialog.getLatestDialog()).isShowing)
            assertTrue(SystemBackupStorage(RuntimeEnvironment.getApplication()).lockNoticePending)
        }
    }

    @Test
    fun acknowledgementClearsTheDurableNoticeAndRemovesTheDialog() {
        host()
        composeRule.onNodeWithText("知道了").performClick()
        composeRule.onNodeWithText("应用锁已关闭").assertDoesNotExist()
        composeRule.onNodeWithText("知道了").assertDoesNotExist()
        composeRule.runOnIdle {
            assertFalse(storage.lockNoticePending)
            assertFalse(SystemBackupStorage(RuntimeEnvironment.getApplication()).lockNoticePending)
            noticeMounted = false
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle { noticeMounted = true }
        composeRule.onNodeWithText("应用锁已关闭").assertDoesNotExist()
    }

    @Test
    fun interruptedCompositionKeepsTheNoticeForTheNextComposition() {
        host()
        composeRule.onNodeWithText("应用锁已关闭").assertIsDisplayed()
        composeRule.runOnIdle { noticeMounted = false }
        composeRule.onNodeWithText("应用锁已关闭").assertDoesNotExist()
        composeRule.runOnIdle {
            assertFalse(assertNotNull(ShadowDialog.getLatestDialog()).isShowing)
            assertTrue(SystemBackupStorage(RuntimeEnvironment.getApplication()).lockNoticePending)
            noticeMounted = true
        }
        composeRule.onNodeWithText("应用锁已关闭").assertIsDisplayed()
        composeRule.runOnIdle { assertTrue(storage.lockNoticePending) }
    }

    private fun host() {
        composeRule.setContent {
            CompositionLocalProvider(LocalSecureWindows provides true) {
                PicaTheme(themeChoice = ThemeChoice.Brand, darkMode = DarkMode.Dark) {
                    if (noticeMounted) RestoreLockNotice()
                }
            }
        }
    }
}
