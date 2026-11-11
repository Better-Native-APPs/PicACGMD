package app.picacgmd.feature.bookshelf

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Label
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.picacgmd.core.data.db.TagFavourite
import app.picacgmd.core.designsystem.component.EmptyState
import app.picacgmd.core.designsystem.component.LoadingState
import app.picacgmd.core.designsystem.window.secureModalBottomSheetProperties
import app.picacgmd.core.designsystem.window.securePopupProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** The bookshelf's secondary tag section (board 22), reached from its toolbar icon. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun TagFavouritesSheet(
    tags: List<TagFavourite>?,
    onDismiss: () -> Unit,
    onOpenTag: (String) -> Unit,
    onRemoveTag: suspend (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var removing by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, properties = secureModalBottomSheetProperties()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
            Text("标签收藏", style = MaterialTheme.typography.titleLarge)
            Text("点标签查看漫画，长按或点更多可移出收藏", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (error != null) Text(error.orEmpty(), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
        }
        when {
            tags == null -> LoadingState()
            tags.isEmpty() -> EmptyState(
                title = "还没有标签收藏",
                description = "在漫画详情页开启「收藏标签」，再点选标签",
                icon = Icons.Outlined.Label,
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
            )
            else -> LazyColumn(Modifier.weight(1f, fill = false).padding(bottom = 16.dp)) {
                items(tags, key = { it.tag }) { favourite ->
                    var menu by remember { mutableStateOf(false) }
                    ListItem(
                        onClick = { onOpenTag(favourite.tag) },
                        enabled = removing != favourite.tag,
                        onLongClick = { menu = true },
                        onLongClickLabel = "管理标签收藏",
                        content = { Text(favourite.tag) },
                        leadingContent = { Icon(Icons.Outlined.Label, null) },
                        trailingContent = {
                            Box {
                                IconButton(onClick = { menu = true }, enabled = removing != favourite.tag) {
                                    Icon(Icons.Outlined.MoreVert, "管理${favourite.tag}")
                                }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, properties = securePopupProperties(focusable = true)) {
                                    DropdownMenuItem(
                                        text = { Text("移出标签收藏") },
                                        leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                                        enabled = removing == null,
                                        onClick = {
                                            menu = false
                                            removing = favourite.tag
                                            error = null
                                            scope.launch {
                                                try {
                                                    onRemoveTag(favourite.tag)
                                                } catch (cancel: CancellationException) {
                                                    throw cancel
                                                } catch (exception: Exception) {
                                                    error = exception.message ?: "移出失败，请重试"
                                                } finally {
                                                    removing = null
                                                }
                                            }
                                        },
                                    )
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}
