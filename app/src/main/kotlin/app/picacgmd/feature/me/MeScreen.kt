package app.picacgmd.feature.me

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Comment
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import app.picacgmd.core.domain.session.PunchInState
import app.picacgmd.core.domain.session.SessionState
import app.picacgmd.core.network.UserProfile
import app.picacgmd.di.LocalAppContainer
import app.picacgmd.feature.bookshelf.safeImageUrl
import app.picacgmd.feature.settings.BackButton
import app.picacgmd.feature.settings.SettingsGroup
import app.picacgmd.feature.settings.SettingsNavRow
import app.picacgmd.feature.settings.SettingsSwitchRow
import app.picacgmd.feature.settings.SettingsWriter
import app.picacgmd.feature.settings.TextInputDialog
import app.picacgmd.feature.settings.rememberAppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class MeDialog { Slogan, Password, Logout }

/**
 * 我的 (board 24): profile card with Cookie avatar, punch-in, account edits and logout.
 * [onBack] is null when 我的 is a top-level destination (large screens).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MeScreen(
    onOpenSettings: () -> Unit,
    onOpenLogin: () -> Unit,
    onOpenMyComments: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenAnnouncements: () -> Unit = {},
    onOpenMessageBoard: () -> Unit = {},
    onBack: (() -> Unit)? = null,
    onLoggedOut: () -> Unit = {},
) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val writer = remember(container) { SettingsWriter(container.settingsRepository, scope, snackbar) }
    val settings = rememberAppSettings()
    val session by container.sessionManager.state.collectAsStateWithLifecycle()
    val profile by container.userRepository.currentProfile.collectAsStateWithLifecycle()
    val punch by container.sessionManager.punchInState.collectAsStateWithLifecycle()
    var loading by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var uploadingAvatar by remember { mutableStateOf(false) }
    var dialog by rememberSaveable { mutableStateOf<MeDialog?>(null) }
    val signedIn = session is SessionState.SignedIn

    fun reload() {
        scope.launch {
            loading = true
            loadError = null
            try {
                container.userRepository.profile()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                loadError = exception.message ?: "资料加载失败"
            } finally {
                loading = false
            }
        }
    }

    fun act(failure: String, success: String?, block: suspend () -> Unit) {
        scope.launch {
            val message = try {
                block()
                success
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                "$failure：${exception.message ?: "请重试"}"
            }
            if (message != null) snackbar.showSnackbar(message)
        }
    }

    LaunchedEffect(signedIn) { if (signedIn && profile == null) reload() }
    var newestAnnouncementId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(signedIn) {
        if (signedIn) {
            newestAnnouncementId = try {
                container.announcementRepository.page(1).docs.firstOrNull()?.id
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                null
            }
        }
    }
    val unreadAnnouncement = newestAnnouncementId != null && settings != null &&
        newestAnnouncementId != settings.lastSeenAnnouncementId
    LaunchedEffect(punch) {
        when (val state = punch) {
            is PunchInState.Done -> reload()
            is PunchInState.Failed -> snackbar.showSnackbar("打卡失败：${state.message}")
            else -> Unit
        }
    }

    val pickAvatar = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            uploadingAvatar = true
            val message = try {
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("读取图片失败")
                }
                container.userRepository.updateAvatar(bytes)
                "头像已更新"
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                "更新头像失败：${exception.message ?: "请重试"}"
            } finally {
                uploadingAvatar = false
            }
            snackbar.showSnackbar(message)
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("我的") },
                navigationIcon = { if (onBack != null) BackButton(onBack) },
                actions = {
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Outlined.Settings, contentDescription = "设置") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = loading && profile != null,
            onRefresh = { if (signedIn) reload() },
            modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
        ) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 24.dp),
            ) {
                item {
                    when {
                        !signedIn -> SignedOutCard(
                            expired = (session as? SessionState.Expired)?.message,
                            onLogin = onOpenLogin,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                        profile != null -> ProfileCard(
                            profile = profile!!,
                            avatarUrl = container.safeImageUrl(profile!!.avatar),
                            uploading = uploadingAvatar,
                            onChangeAvatar = { pickAvatar.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                        loadError != null -> ProfileError(loadError!!, ::reload, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                        else -> Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) { LoadingIndicator() }
                    }
                }
                if (signedIn) {
                    item {
                        PunchCard(
                            punched = profile?.isPunched == true || punch is PunchInState.Done,
                            punching = punch is PunchInState.Punching,
                            autoPunchIn = settings?.autoPunchIn ?: true,
                            onPunch = { act("打卡失败", "打卡成功") { container.sessionManager.punchIn() } },
                            onAutoPunchIn = { value -> writer.update { setAutoPunchIn(value) } },
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    item {
                        SettingsGroup("社区") {
                            row { shapes ->
                                SettingsNavRow(
                                    shapes, "公告", onOpenAnnouncements,
                                    summary = if (unreadAnnouncement) "有新公告" else "官方通知和活动",
                                    leading = {
                                        BadgedBox(badge = { if (unreadAnnouncement) Badge() }) {
                                            Icon(Icons.Outlined.Campaign, contentDescription = null)
                                        }
                                    },
                                )
                            }
                            row { shapes ->
                                SettingsNavRow(
                                    shapes, "嗶咔留言板", onOpenMessageBoard,
                                    summary = "和大家聊聊天、反馈问题",
                                    leading = { Icon(Icons.Outlined.Forum, contentDescription = null) },
                                )
                            }
                        }
                    }
                    item {
                        SettingsGroup("账号") {
                            row { shapes ->
                                SettingsNavRow(
                                    shapes, "我的评论", onOpenMyComments,
                                    leading = { Icon(Icons.AutoMirrored.Outlined.Comment, contentDescription = null) },
                                )
                            }
                            row { shapes ->
                                SettingsNavRow(
                                    shapes, "修改签名", { dialog = MeDialog.Slogan },
                                    summary = profile?.slogan?.takeIf { it.isNotBlank() } ?: "还没有签名",
                                    leading = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                                )
                            }
                            row { shapes ->
                                SettingsNavRow(
                                    shapes, "修改头像", { pickAvatar.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                                    summary = "会裁剪成正方形后上传",
                                    leading = { Icon(Icons.Outlined.CameraAlt, contentDescription = null) },
                                    enabled = !uploadingAvatar,
                                )
                            }
                            row { shapes ->
                                SettingsNavRow(
                                    shapes, "修改密码", { dialog = MeDialog.Password },
                                    leading = { Icon(Icons.Outlined.Password, contentDescription = null) },
                                )
                            }
                        }
                    }
                    item {
                        SettingsGroup(null, Modifier.padding(top = 16.dp)) {
                            row { shapes ->
                                SettingsNavRow(
                                    shapes, "退出登录", { dialog = MeDialog.Logout },
                                    summary = (session as? SessionState.SignedIn)?.email,
                                    showChevron = false,
                                    leading = { Icon(Icons.AutoMirrored.Outlined.Logout, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                                )
                            }
                        }
                    }
                } else if (settings != null) {
                    item {
                        SettingsGroup("打卡") {
                            row { shapes ->
                                SettingsSwitchRow(
                                    shapes, "打开应用时自动打卡", settings.autoPunchIn, { writer.update { setAutoPunchIn(it) } },
                                    leading = { Icon(Icons.Outlined.CalendarMonth, contentDescription = null) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    when (dialog) {
        MeDialog.Slogan -> TextInputDialog(
            title = "修改签名",
            label = "签名",
            initial = profile?.slogan.orEmpty(),
            singleLine = false,
            validate = { if (it.length > 100) "签名最多 100 个字" else null },
            onSave = { value -> act("修改失败", "签名已更新") { container.userRepository.updateSlogan(value.trim()) } },
            onDismiss = { dialog = null },
        )
        MeDialog.Password -> PasswordDialog(
            onSave = { old, new -> act("修改失败", "密码已修改") { container.userRepository.updatePassword(old, new) } },
            onDismiss = { dialog = null },
        )
        MeDialog.Logout -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("退出登录？") },
            text = { Text("浏览记录和下载会保留在这台设备上。") },
            confirmButton = {
                Button(
                    onClick = {
                        dialog = null
                        act("退出失败", null) {
                            container.sessionManager.logout()
                            onLoggedOut()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text("退出登录") }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("取消") } },
        )
        null -> Unit
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ProfileCard(
    profile: UserProfile,
    avatarUrl: String?,
    uploading: Boolean,
    onChangeAvatar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val avatarShape = MaterialShapes.Cookie9Sided.toShape()
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = colors.primaryContainer, contentColor = colors.onPrimaryContainer),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(contentAlignment = Alignment.Center) {
                    Box(
                        Modifier
                            .size(88.dp)
                            .clip(avatarShape)
                            .background(colors.primary),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (avatarUrl != null) {
                            AsyncImage(
                                model = avatarUrl,
                                contentDescription = "头像",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            Icon(Icons.Outlined.AccountCircle, contentDescription = null, tint = colors.onPrimary, modifier = Modifier.size(48.dp))
                        }
                    }
                    if (uploading) LoadingIndicator(color = colors.onPrimary)
                    IconButton(
                        onClick = onChangeAvatar,
                        enabled = !uploading,
                        modifier = Modifier.align(Alignment.BottomEnd).size(32.dp).clip(MaterialTheme.shapes.extraLarge).background(colors.surface),
                    ) { Icon(Icons.Outlined.CameraAlt, contentDescription = "修改头像", tint = colors.primary, modifier = Modifier.size(18.dp)) }
                }
                Column(Modifier.weight(1f).padding(start = 20.dp)) {
                    Text(profile.name, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (profile.title.isNotBlank()) {
                        Text(profile.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Lv.${profile.level}",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                            color = colors.onPrimary,
                            modifier = Modifier.background(colors.primary, MaterialTheme.shapes.extraLarge).padding(horizontal = 10.dp, vertical = 2.dp),
                        )
                        Text("经验 ${profile.exp}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Text(
                profile.slogan?.takeIf { it.isNotBlank() } ?: "还没有签名",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onPrimaryContainer.copy(alpha = if (profile.slogan.isNullOrBlank()) 0.6f else 1f),
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}

@Composable
private fun SignedOutCard(expired: String?, onLogin: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(if (expired != null) "登录已过期" else "还没有登录", style = MaterialTheme.typography.titleLarge)
            Text(
                expired ?: "登录后可以看漫画、收藏和打卡。浏览记录和下载不需要登录。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
            )
            Button(onClick = onLogin) { Text(if (expired != null) "重新登录" else "登录") }
        }
    }
}

@Composable
private fun ProfileError(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer),
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("资料加载失败：$message", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onRetry) { Text("重试") }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PunchCard(
    punched: Boolean,
    punching: Boolean,
    autoPunchIn: Boolean,
    onPunch: () -> Unit,
    onAutoPunchIn: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = if (punched) colors.tertiaryContainer else colors.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (punched) Icons.Outlined.CheckCircle else Icons.Outlined.CalendarMonth,
                    contentDescription = null,
                    tint = if (punched) colors.onTertiaryContainer else colors.primary,
                )
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(if (punched) "今天已打卡" else "今天还没打卡", style = MaterialTheme.typography.titleMedium)
                    Text("打卡可以获得经验", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
                when {
                    punching -> LoadingIndicator(Modifier.size(40.dp))
                    !punched -> FilledTonalButton(onClick = onPunch) { Text("打卡") }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("打开应用时自动打卡", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(checked = autoPunchIn, onCheckedChange = onAutoPunchIn)
            }
        }
    }
}

@Composable
private fun PasswordDialog(onSave: (String, String) -> Unit, onDismiss: () -> Unit) {
    var old by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    val error = when {
        new.isNotEmpty() && new.length < 8 -> "新密码至少 8 位"
        confirm.isNotEmpty() && confirm != new -> "两次输入的新密码不一致"
        else -> null
    }
    val transformation = if (visible) VisualTransformation.None else PasswordVisualTransformation()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("修改密码") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    Triple("当前密码", old) { v: String -> old = v },
                    Triple("新密码", new) { v: String -> new = v },
                    Triple("再输一次新密码", confirm) { v: String -> confirm = v },
                ).forEachIndexed { index, (label, value, change) ->
                    OutlinedTextField(
                        value = value,
                        onValueChange = change,
                        label = { Text(label) },
                        singleLine = true,
                        visualTransformation = transformation,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = if (index == 0) {
                            {
                                IconButton(onClick = { visible = !visible }) {
                                    Icon(
                                        if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                        contentDescription = if (visible) "隐藏密码" else "显示密码",
                                    )
                                }
                            }
                        } else {
                            null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Text(
                    "修改后，这台设备上保存的登录密码会一起更新。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = old.isNotEmpty() && new.length >= 8 && confirm == new,
                onClick = {
                    onSave(old, new)
                    onDismiss()
                },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
