package app.picacgmd.feature.bookshelf

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.picacgmd.core.data.db.LocalFavouriteFolder
import app.picacgmd.core.designsystem.window.secureDialogProperties
import app.picacgmd.core.designsystem.window.secureModalBottomSheetProperties
import app.picacgmd.core.designsystem.window.securePopupProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Shared by the detail action and the bookshelf's folder manager. */
@Composable
internal fun LocalFolderNameDialog(
    title: String,
    onDismiss: () -> Unit,
    onConfirm: suspend (String) -> Unit,
    initialName: String = "",
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        properties = secureDialogProperties(),
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it; error = null },
                label = { Text("收藏夹名称") },
                singleLine = true,
                enabled = !saving,
                isError = error != null,
                supportingText = error?.let { message -> { Text(message) } },
            )
        },
        confirmButton = {
            Button(enabled = name.isNotBlank() && !saving, onClick = {
                saving = true
                scope.launch {
                    try {
                        onConfirm(name.trim())
                        onDismiss()
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (exception: Exception) {
                        error = exception.message ?: "保存失败，请重试"
                    } finally {
                        saving = false
                    }
                }
            }) { Text(if (saving) "正在保存" else "保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("取消") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LocalFavouritePickerSheet(
    folders: List<LocalFavouriteFolder>,
    selectedFolderId: String?,
    onDismiss: () -> Unit,
    onCreateFolder: suspend (String) -> String,
    onSelectFolder: suspend (String) -> Unit,
    onRemove: (suspend () -> Unit)? = null,
    title: String = "本地收藏",
) {
    var creating by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun save(action: suspend () -> Unit) {
        if (saving) return
        saving = true
        error = null
        scope.launch {
            try {
                action()
                onDismiss()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (exception: Exception) {
                error = exception.message ?: "保存失败，请重试"
            } finally {
                saving = false
            }
        }
    }

    ModalBottomSheet(onDismissRequest = { if (!saving) onDismiss() }, properties = secureModalBottomSheetProperties()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text("选择收藏夹后立即保存。仅保存在本机，不影响在线收藏。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { creating = true }, enabled = !saving) {
                Icon(Icons.Outlined.Add, null)
                Text("新建收藏夹", Modifier.padding(start = 8.dp))
            }
        }
        LazyColumn(Modifier.weight(1f, fill = false).selectableGroup()) {
            items(folders, key = { it.folderId }) { folder ->
                ListItem(
                    headlineContent = { Text(folder.name) },
                    leadingContent = { RadioButton(selected = folder.folderId == selectedFolderId, onClick = null, enabled = !saving) },
                    modifier = Modifier.selectable(
                        selected = folder.folderId == selectedFolderId,
                        enabled = !saving,
                        role = Role.RadioButton,
                        onClick = { save { onSelectFolder(folder.folderId) } },
                    ),
                )
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 16.dp)) {
            if (saving) Text("正在保存", style = MaterialTheme.typography.bodyMedium)
            if (error != null) Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
            if (selectedFolderId != null && onRemove != null) {
                TextButton(onClick = { save(onRemove) }, enabled = !saving, modifier = Modifier.fillMaxWidth()) {
                    Text("移出本地收藏", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
    if (creating) LocalFolderNameDialog(
        title = "新建收藏夹",
        onDismiss = { creating = false },
        onConfirm = { onCreateFolder(it) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LocalFolderManagerSheet(
    folders: List<LocalFavouriteFolder>,
    onDismiss: () -> Unit,
    onCreateFolder: suspend (String) -> String,
    onRenameFolder: suspend (String, String) -> Unit,
    onDeleteFolder: suspend (String, String) -> Unit,
) {
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<LocalFavouriteFolder?>(null) }
    var deleting by remember { mutableStateOf<LocalFavouriteFolder?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, properties = secureModalBottomSheetProperties()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            Text("管理收藏夹", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = { creating = true }) { Icon(Icons.Outlined.Add, "新建收藏夹") }
        }
        LazyColumn(Modifier.weight(1f, fill = false).padding(bottom = 16.dp)) {
            items(folders, key = { it.folderId }) { folder ->
                var menu by remember { mutableStateOf(false) }
                ListItem(
                    headlineContent = { Text(folder.name) },
                    leadingContent = { Icon(Icons.Outlined.Folder, null) },
                    trailingContent = {
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "管理${folder.name}") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, properties = securePopupProperties(focusable = true)) {
                                DropdownMenuItem(
                                    text = { Text("重命名") },
                                    leadingIcon = { Icon(Icons.Outlined.DriveFileRenameOutline, null) },
                                    onClick = { menu = false; renaming = folder },
                                )
                                DropdownMenuItem(
                                    text = { Text("删除收藏夹") },
                                    leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                                    enabled = folder.folderId != LocalFavouriteFolder.DEFAULT_ID,
                                    onClick = { menu = false; deleting = folder },
                                )
                            }
                        }
                    },
                )
            }
        }
    }
    if (creating) LocalFolderNameDialog(
        title = "新建收藏夹",
        onDismiss = { creating = false },
        onConfirm = { onCreateFolder(it) },
    )
    renaming?.let { folder ->
        LocalFolderNameDialog(
            title = "重命名收藏夹",
            initialName = folder.name,
            onDismiss = { renaming = null },
            onConfirm = { onRenameFolder(folder.folderId, it) },
        )
    }
    deleting?.let { folder ->
        DeleteLocalFolderDialog(
            folder = folder,
            destinations = folders.filter { it.folderId != folder.folderId },
            onDismiss = { deleting = null },
            onConfirm = { onDeleteFolder(folder.folderId, it) },
        )
    }
}

@Composable
private fun DeleteLocalFolderDialog(
    folder: LocalFavouriteFolder,
    destinations: List<LocalFavouriteFolder>,
    onDismiss: () -> Unit,
    onConfirm: suspend (String) -> Unit,
) {
    var target by rememberSaveable { mutableStateOf(LocalFavouriteFolder.DEFAULT_ID) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        properties = secureDialogProperties(),
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("删除「${folder.name}」？") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("里面的漫画将移到所选收藏夹，下载不受影响。")
                LazyColumn(Modifier.heightIn(max = 320.dp).selectableGroup()) {
                    items(destinations, key = { it.folderId }) { destination ->
                        ListItem(
                            headlineContent = { Text(destination.name) },
                            leadingContent = { RadioButton(selected = target == destination.folderId, onClick = null) },
                            modifier = Modifier.selectable(
                                selected = target == destination.folderId,
                                enabled = !saving,
                                role = Role.RadioButton,
                                onClick = { target = destination.folderId; error = null },
                            ),
                        )
                    }
                }
                if (error != null) Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            Button(enabled = !saving && destinations.any { it.folderId == target }, onClick = {
                saving = true
                scope.launch {
                    try {
                        onConfirm(target)
                        onDismiss()
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (exception: Exception) {
                        error = exception.message ?: "删除失败，请重试"
                    } finally {
                        saving = false
                    }
                }
            }) { Text(if (saving) "正在移动" else "移动并删除") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("取消") } },
    )
}
