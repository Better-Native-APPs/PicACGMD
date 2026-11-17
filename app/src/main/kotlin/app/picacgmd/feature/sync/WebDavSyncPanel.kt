package app.picacgmd.feature.sync

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.picacgmd.core.designsystem.component.AppButton
import app.picacgmd.core.designsystem.component.AppIconButton
import app.picacgmd.core.designsystem.component.AppTextButton
import app.picacgmd.core.designsystem.window.secureDialogProperties
import app.picacgmd.core.domain.sync.HistorySyncCounts
import app.picacgmd.core.network.webdav.WebDavError
import app.picacgmd.di.LocalAppContainer
import app.picacgmd.feature.settings.SettingsGroup
import app.picacgmd.feature.settings.SettingsGroupTitle
import app.picacgmd.feature.settings.SettingsNavRow
import app.picacgmd.feature.settings.SettingsNoteRow
import app.picacgmd.feature.settings.SettingsPageScaffold
import app.picacgmd.feature.settings.SettingsSwitchRow
import app.picacgmd.feature.settings.ShapeBadge

internal const val WEBDAV_PANEL_TITLE = "WebDAV 云同步"

internal object WebDavPanelTags {
    const val Status = "webdav-status"
    const val Progress = "webdav-progress"
    const val Counts = "webdav-counts"
    const val Address = "webdav-address"
    const val Username = "webdav-username"
    const val Password = "webdav-password"
    const val Folder = "webdav-folder"
}

private enum class WebDavConfirm { OverwriteRemote, OverwriteLocal, DiscardEdits }

private val DraftSaver = listSaver<WebDavAccountDraft?, String>(
    save = { draft -> if (draft == null) emptyList() else listOf(draft.address, draft.username, draft.folder) },
    restore = { saved -> if (saved.size == 3) WebDavAccountDraft(saved[0], saved[1], saved[2]) else null },
)

/**
 * The sync panel as a settings sub page. Its holder belongs to the activity, not to the settings
 * destination: a sync started here keeps running and keeps its result after the user leaves Settings.
 */
@Composable
internal fun WebDavSyncPage(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val container = LocalAppContainer.current
    val owner = LocalActivity.current as? ViewModelStoreOwner ?: checkNotNull(LocalViewModelStoreOwner.current)
    val factory = remember(container) { viewModelFactory { initializer { WebDavSyncViewModel(container.webDavSync) } } }
    val viewModel: WebDavSyncViewModel = viewModel(viewModelStoreOwner = owner, key = "webdav-sync", factory = factory)
    WebDavSyncPanel(viewModel, onBack, modifier)
}

/**
 * Status card with the two everyday actions, the account (a summary row once saved, the form while
 * unset or being changed), the automatic switches, the two overwrites and the scope note.
 *
 * The password lives only in this composition: it survives a rotation (the activity keeps its
 * composition through one) and is gone after anything that rebuilds the activity. The other three
 * fields are kept in saved state while they differ from the stored account.
 */
@Composable
internal fun WebDavSyncPanel(
    viewModel: WebDavSyncViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    now: () -> Long = System::currentTimeMillis,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val focusManager = LocalFocusManager.current

    // The fields own what is typed and hand every change to the holder at once, so the keyboard never
    // waits for a state round trip; they follow the holder only when it changes for another reason.
    var address by remember { mutableStateOf(viewModel.state.value.account.address) }
    var username by remember { mutableStateOf(viewModel.state.value.account.username) }
    var folder by remember { mutableStateOf(viewModel.state.value.account.folder) }
    var password by remember { mutableStateOf("") }
    LaunchedEffect(state.account) {
        val current = viewModel.state.value.account
        if (address != current.address) address = current.address
        if (username != current.username) username = current.username
        if (folder != current.folder) folder = current.folder
    }

    // Not saved state: coming back to the page shows the form only when there is something unsaved in it.
    var editing by remember { mutableStateOf(false) }
    var attempted by remember { mutableStateOf(false) }
    var saveWasLast by remember { mutableStateOf(false) }
    var confirm by rememberSaveable { mutableStateOf<WebDavConfirm?>(null) }

    // Unsaved edits outlive the process: kept here while they differ from the stored account and
    // typed back into a new holder once it has read that account.
    var keptDraft by rememberSaveable(stateSaver = DraftSaver) { mutableStateOf<WebDavAccountDraft?>(null) }
    var restorePending by remember { mutableStateOf(keptDraft != null) }
    LaunchedEffect(state.ready, state.dirty, state.account, restorePending) {
        if (!restorePending) {
            keptDraft = state.account.takeIf { state.dirty }
        } else if (state.ready) {
            val kept = keptDraft
            if (kept != null && !state.dirty && kept != state.account) {
                viewModel.editAddress(kept.address)
                viewModel.editUsername(kept.username)
                viewModel.editFolder(kept.folder)
            }
            restorePending = false
        }
    }

    val forget: () -> Unit = {
        password = ""
        attempted = false
        editing = false
        keptDraft = null
    }
    var seenSaves by remember { mutableIntStateOf(viewModel.state.value.accountSaves) }
    LaunchedEffect(state.accountSaves) {
        if (state.accountSaves != seenSaves) {
            seenSaves = state.accountSaves
            password = ""
            attempted = false
            if (!viewModel.state.value.dirty) editing = false
        }
    }
    // A banner belongs to the visit that caused it.
    DisposableEffect(viewModel) {
        onDispose {
            viewModel.clearMessage()
            viewModel.clearError()
        }
    }

    val configured = state.hasPassword
    val formOpen = state.ready && (!configured || editing || state.dirty)
    // A typed password is an edit too: test and sync would still use the stored one.
    val unsaved = state.dirty || password.isNotEmpty()
    val canAct = configured && !unsaved && !state.busy
    val draft = WebDavAccountDraft(address, username, folder)
    val passwordKept = state.hasPassword && sameWebDavAccount(draft, state.savedAccount)
    val problems = validateWebDavAccount(draft, password, passwordRequired = !passwordKept)
    val errorInForm = formOpen && saveWasLast
    val refused = if (errorInForm) webDavFieldError(state.error) else null
    val bannerError = state.error.takeIf { !errorInForm }
    // 「同步完成」 would only repeat what the card already says.
    val bannerMessage = state.message.takeIf { state.lastResult?.successful != true || it != state.lastResult?.message }
    val summary = state.statusSummary(now(), shownError = bannerError)

    val focus = remember { WebDavField.entries.associateWith { FocusRequester() } }
    val save: () -> Unit = {
        attempted = true
        saveWasLast = true
        val firstProblem = WebDavField.entries.firstOrNull { it in problems }
        if (firstProblem != null) focus.getValue(firstProblem).requestFocus() else viewModel.saveAccount(password.ifEmpty { null })
    }
    val act: (() -> Unit) -> Unit = { action ->
        saveWasLast = false
        action()
    }
    val leave: () -> Unit = { if (unsaved && !state.saving) confirm = WebDavConfirm.DiscardEdits else onBack() }
    BackHandler(enabled = unsaved && !state.saving) { confirm = WebDavConfirm.DiscardEdits }

    SettingsPageScaffold(WEBDAV_PANEL_TITLE, leave, snackbar, modifier.imePadding()) {
        item(key = "status") {
            StatusCard(
                ready = state.ready,
                summary = summary,
                error = bannerError,
                message = bannerMessage,
                onDismissError = viewModel::clearError,
                onDismissMessage = viewModel::clearMessage,
                showActions = configured,
                actionsEnabled = canAct,
                unsaved = configured && unsaved,
                onSync = { act(viewModel::syncNow) },
                onTest = { act(viewModel::testConnection) },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        if (!state.ready) return@SettingsPageScaffold
        item(key = "account") {
            if (formOpen) {
                AccountForm(
                    address = address,
                    username = username,
                    password = password,
                    folder = folder,
                    onAddress = { address = it; viewModel.editAddress(it) },
                    onUsername = { username = it; viewModel.editUsername(it) },
                    onPassword = { password = it },
                    onFolder = { folder = it; viewModel.editFolder(it) },
                    error = { field ->
                        val problem = refused?.takeIf { it.field() == field }
                            ?: problems[field]?.takeIf { attempted || it == WebDavError.HTTPS_REQUIRED }
                        problem?.message()
                    },
                    formError = state.error.takeIf { errorInForm && refused == null },
                    onDismissError = viewModel::clearError,
                    passwordHint = when {
                        !state.hasPassword -> "加密保存在本机"
                        passwordKept -> "已保存，留空则不修改"
                        else -> "地址或用户名变了，请重新输入密码"
                    },
                    focus = focus,
                    saving = state.saving,
                    saveEnabled = !state.busy,
                    onSave = save,
                    onCancel = if (configured) {
                        {
                            focusManager.clearFocus()
                            viewModel.discardEdits()
                            forget()
                        }
                    } else {
                        null
                    },
                )
            } else {
                SettingsGroup("账号") {
                    row { shapes ->
                        SettingsNavRow(
                            shapes, state.savedAccount.address, { editing = true },
                            summary = "用户名 ${state.savedAccount.username} · 文件夹 ${state.savedAccount.folder}",
                            value = "修改",
                            leading = { Icon(Icons.Outlined.Dns, contentDescription = null) },
                        )
                    }
                }
            }
        }
        if (configured) {
            item(key = "automatic") {
                SettingsGroup("自动化") {
                    row { shapes ->
                        SettingsSwitchRow(
                            shapes, "自动同步", state.autoSync, { act { viewModel.setAutoSync(it) } },
                            summary = "打开应用、退出阅读后自动同步",
                            enabled = !state.busy,
                        )
                    }
                    row { shapes ->
                        SettingsSwitchRow(
                            shapes, "仅在 Wi-Fi 下自动同步", state.wifiOnly, { act { viewModel.setWifiOnly(it) } },
                            summary = if (state.autoSync) "手动同步不受限制" else "开启自动同步后可用",
                            enabled = state.autoSync && !state.busy,
                        )
                    }
                }
            }
            item(key = "overwrite") {
                SettingsGroup("覆盖") {
                    row { shapes ->
                        SettingsNavRow(
                            shapes, "用本机覆盖云端", { confirm = WebDavConfirm.OverwriteRemote },
                            summary = "云端的记录全部换成本机的",
                            enabled = canAct,
                            leading = { Icon(Icons.Outlined.CloudUpload, contentDescription = null) },
                        )
                    }
                    row { shapes ->
                        SettingsNavRow(
                            shapes, "用云端覆盖本机", { confirm = WebDavConfirm.OverwriteLocal },
                            summary = "本机的记录全部换成云端的",
                            enabled = canAct,
                            leading = { Icon(Icons.Outlined.CloudDownload, contentDescription = null) },
                        )
                    }
                }
            }
        }
        item(key = "scope") {
            SettingsGroup(null, Modifier.padding(top = 20.dp)) {
                row { shapes ->
                    SettingsNoteRow(shapes, "只同步浏览记录、阅读进度和置顶，其他数据都留在本机。", Icons.Outlined.Info)
                }
            }
        }
    }

    when (confirm) {
        WebDavConfirm.OverwriteRemote -> ConfirmDialog(
            title = "用本机覆盖云端？",
            text = "云端的浏览记录、阅读进度和置顶会全部换成本机的，其他设备下次同步时也会跟着变。覆盖后无法撤销。",
            confirmLabel = "覆盖云端",
            dismissLabel = "取消",
            onConfirm = { act(viewModel::overwriteRemote) },
            onDismiss = { confirm = null },
        )
        WebDavConfirm.OverwriteLocal -> ConfirmDialog(
            title = "用云端覆盖本机？",
            text = "本机的浏览记录、阅读进度和置顶会全部换成云端的，只在本机有的记录会被删除。覆盖后无法撤销。",
            confirmLabel = "覆盖本机",
            dismissLabel = "取消",
            onConfirm = { act(viewModel::overwriteLocal) },
            onDismiss = { confirm = null },
        )
        WebDavConfirm.DiscardEdits -> ConfirmDialog(
            title = "放弃未保存的修改？",
            text = "账号的修改还没有保存。",
            confirmLabel = "放弃",
            dismissLabel = "继续编辑",
            onConfirm = {
                viewModel.discardEdits()
                forget()
                onBack()
            },
            onDismiss = { confirm = null },
        )
        null -> Unit
    }
}

/**
 * What the sync is doing or how it last ended, the banner of the latest action and the two everyday
 * actions. The headline and its lines are one live region, so a screen reader follows a run.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
private fun StatusCard(
    ready: Boolean,
    summary: WebDavStatusSummary,
    error: String?,
    message: String?,
    onDismissError: () -> Unit,
    onDismissMessage: () -> Unit,
    showActions: Boolean,
    actionsEnabled: Boolean,
    unsaved: Boolean,
    onSync: () -> Unit,
    onTest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(20.dp)) {
            if (!ready) {
                Box(Modifier.fillMaxWidth().height(96.dp), contentAlignment = Alignment.Center) { LoadingIndicator() }
                return@Column
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .testTag(WebDavPanelTags.Status)
                    .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Crossfade(summary.tone, label = "syncTone") { tone -> StatusBadge(tone) }
                Column(Modifier.padding(start = 16.dp).weight(1f)) {
                    Text(summary.headline, style = MaterialTheme.typography.titleLarge, color = colors.onSurface)
                    summary.lines.forEach { line ->
                        Text(line, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    }
                }
            }
            if (summary.progress != null) {
                LinearWavyProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                        .testTag(WebDavPanelTags.Progress)
                        .semantics { stateDescription = summary.progress },
                )
            }
            summary.counts?.let { CountsRow(it, Modifier.padding(top = 16.dp)) }
            if (error != null) {
                Banner(
                    error, Icons.Outlined.ErrorOutline, colors.errorContainer, colors.onErrorContainer,
                    dismissLabel = "关闭提示", onDismiss = onDismissError, modifier = Modifier.padding(top = 16.dp),
                )
            } else if (message != null) {
                Banner(
                    message, Icons.Outlined.CheckCircle, colors.secondaryContainer, colors.onSecondaryContainer,
                    dismissLabel = "关闭提示", onDismiss = onDismissMessage, modifier = Modifier.padding(top = 16.dp),
                )
            }
            if (showActions) {
                if (unsaved) {
                    Text(
                        "账号的修改还没保存，保存或取消后才能同步",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
                // Wraps instead of squeezing: at a large font the second action drops to its own line.
                FlowRow(
                    modifier = Modifier.padding(top = if (unsaved) 4.dp else 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AppButton(onClick = onSync, enabled = actionsEnabled) {
                        Icon(Icons.Outlined.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("立即同步")
                    }
                    AppTextButton(onClick = onTest, enabled = actionsEnabled) { Text("测试连接") }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StatusBadge(tone: WebDavTone) {
    val colors = MaterialTheme.colorScheme
    val (icon: ImageVector, shape: RoundedPolygon) = when (tone) {
        WebDavTone.Unset -> Icons.Outlined.Cloud to MaterialShapes.Circle
        WebDavTone.Ready -> Icons.Outlined.CloudSync to MaterialShapes.Cookie6Sided
        WebDavTone.Busy -> Icons.Outlined.CloudSync to MaterialShapes.Cookie6Sided
        WebDavTone.Done -> Icons.Outlined.CloudDone to MaterialShapes.Sunny
        WebDavTone.Failed -> Icons.Outlined.ErrorOutline to MaterialShapes.Cookie4Sided
    }
    val (container, content) = when (tone) {
        WebDavTone.Unset -> colors.surfaceContainerHighest to colors.onSurfaceVariant
        WebDavTone.Ready, WebDavTone.Busy -> colors.primaryContainer to colors.onPrimaryContainer
        WebDavTone.Done -> colors.tertiaryContainer to colors.onTertiaryContainer
        WebDavTone.Failed -> colors.errorContainer to colors.onErrorContainer
    }
    if (tone == WebDavTone.Busy) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            LoadingIndicator(Modifier.size(48.dp).clearAndSetSemantics { })
        }
    } else {
        ShapeBadge(icon, shape, container, content, size = 48)
    }
}

/** The three numbers of a sync that changed something, read out as one sentence. */
@Composable
private fun CountsRow(counts: HistorySyncCounts, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .testTag(WebDavPanelTags.Counts)
            .clearAndSetSemantics { contentDescription = counts.spoken() },
    ) {
        listOf("传到云端" to counts.uploaded, "存到本机" to counts.downloaded, "已删除" to counts.removed).forEach { (label, count) ->
            Column(Modifier.weight(1f)) {
                Text("$count", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
                Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** The outcome of the latest action; announced when it appears and closed with its own button. */
@Composable
private fun Banner(
    text: String,
    icon: ImageVector,
    container: Color,
    content: Color,
    dismissLabel: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(container, MaterialTheme.shapes.large)
            .padding(start = 16.dp, top = 4.dp, end = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = content)
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = content,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
        AppIconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, contentDescription = dismissLabel, tint = content) }
    }
}

/**
 * The four fields on the group's own surface. Each keyboard's action goes to the next field and the
 * last one saves; a field shows what is wrong with it under itself, anything else in a banner above
 * the buttons.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AccountForm(
    address: String,
    username: String,
    password: String,
    folder: String,
    onAddress: (String) -> Unit,
    onUsername: (String) -> Unit,
    onPassword: (String) -> Unit,
    onFolder: (String) -> Unit,
    error: (WebDavField) -> String?,
    formError: String?,
    onDismissError: () -> Unit,
    passwordHint: String,
    focus: Map<WebDavField, FocusRequester>,
    saving: Boolean,
    saveEnabled: Boolean,
    onSave: () -> Unit,
    onCancel: (() -> Unit)?,
) {
    var passwordVisible by remember { mutableStateOf(false) }
    val plain = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Next)
    val next: (WebDavField) -> KeyboardActions = { field -> KeyboardActions(onNext = { focus.getValue(field).requestFocus() }) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        SettingsGroupTitle("账号")
        Surface(
            shape = ListItemDefaults.segmentedShapes(index = 0, count = 1).shape,
            color = ListItemDefaults.segmentedColors().containerColor,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val addressError = error(WebDavField.Address)
                OutlinedTextField(
                    value = address,
                    onValueChange = onAddress,
                    modifier = Modifier.fillMaxWidth().focusRequester(focus.getValue(WebDavField.Address)).testTag(WebDavPanelTags.Address),
                    readOnly = saving,
                    singleLine = true,
                    label = { Text("服务器地址") },
                    placeholder = { Text("https://") },
                    isError = addressError != null,
                    supportingText = { Text(addressError ?: "仅支持 https:// 开头的地址") },
                    keyboardOptions = plain.copy(keyboardType = KeyboardType.Uri),
                    keyboardActions = next(WebDavField.Username),
                )
                val usernameError = error(WebDavField.Username)
                OutlinedTextField(
                    value = username,
                    onValueChange = onUsername,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus.getValue(WebDavField.Username))
                        .testTag(WebDavPanelTags.Username)
                        .semantics { contentType = ContentType.Username },
                    readOnly = saving,
                    singleLine = true,
                    label = { Text("用户名") },
                    isError = usernameError != null,
                    supportingText = usernameError?.let { { Text(it) } },
                    keyboardOptions = plain,
                    keyboardActions = next(WebDavField.Password),
                )
                val passwordError = error(WebDavField.Password)
                OutlinedTextField(
                    value = password,
                    onValueChange = onPassword,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus.getValue(WebDavField.Password))
                        .testTag(WebDavPanelTags.Password)
                        .semantics { contentType = ContentType.Password },
                    readOnly = saving,
                    singleLine = true,
                    label = { Text("密码") },
                    isError = passwordError != null,
                    supportingText = { Text(passwordError ?: passwordHint) },
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        AppIconButton({ passwordVisible = !passwordVisible }) {
                            Icon(
                                if (passwordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = if (passwordVisible) "隐藏密码" else "显示密码",
                            )
                        }
                    },
                    keyboardOptions = plain.copy(keyboardType = KeyboardType.Password),
                    keyboardActions = next(WebDavField.Folder),
                )
                val folderError = error(WebDavField.Folder)
                OutlinedTextField(
                    value = folder,
                    onValueChange = onFolder,
                    modifier = Modifier.fillMaxWidth().focusRequester(focus.getValue(WebDavField.Folder)).testTag(WebDavPanelTags.Folder),
                    readOnly = saving,
                    singleLine = true,
                    label = { Text("文件夹") },
                    isError = folderError != null,
                    supportingText = { Text(folderError ?: "记录存放在服务器的这个文件夹里") },
                    keyboardOptions = plain.copy(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (saveEnabled) onSave() }),
                )
                if (formError != null) {
                    Banner(
                        formError, Icons.Outlined.ErrorOutline,
                        MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer,
                        dismissLabel = "关闭提示", onDismiss = onDismissError, modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (onCancel != null) AppTextButton(onClick = onCancel, enabled = !saving) { Text("取消") }
                    AppButton(onClick = onSave, enabled = saveEnabled) {
                        if (saving) {
                            LoadingIndicator(Modifier.size(18.dp).clearAndSetSemantics { }, color = LocalContentColor.current)
                            Spacer(Modifier.width(8.dp))
                            Text("正在保存")
                        } else {
                            Text("保存")
                        }
                    }
                }
            }
        }
    }
}

/** A choice that throws something away: the confirming button carries the error colour. */
@Composable
private fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    dismissLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        properties = secureDialogProperties(),
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            AppButton(
                onClick = {
                    onDismiss()
                    onConfirm()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) { Text(confirmLabel) }
        },
        dismissButton = { AppTextButton(onClick = onDismiss) { Text(dismissLabel) } },
    )
}
