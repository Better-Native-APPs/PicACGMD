package app.picacgmd.feature.bookshelf

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BookmarkRemove
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.picacgmd.core.data.db.LocalFavouriteEntry
import app.picacgmd.core.data.db.LocalFavouriteFolder
import app.picacgmd.core.designsystem.component.ComicListItem
import app.picacgmd.core.designsystem.component.EmptyState
import app.picacgmd.core.designsystem.component.LoadingState
import app.picacgmd.di.LocalAppContainer

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun LocalFavouritesView(
    folders: List<LocalFavouriteFolder>?,
    comics: List<LocalFavouriteEntry>?,
    folderId: String?,
    newestFirst: Boolean,
    selection: Set<String>,
    onFolderSelect: (String?) -> Unit,
    onNewFolder: () -> Unit,
    onSort: (Boolean) -> Unit,
    onToggle: (String) -> Unit,
    onOpenComic: (String) -> Unit,
    onMove: (LocalFavouriteEntry) -> Unit,
    onRemove: (List<String>) -> Unit,
) {
    val container = LocalAppContainer.current
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            LazyRow(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "all") { FilterChip(selected = folderId == null, onClick = { onFolderSelect(null) }, label = { Text("全部") }) }
                items(folders.orEmpty(), key = { it.folderId }) { folder ->
                    FilterChip(selected = folderId == folder.folderId, onClick = { onFolderSelect(folder.folderId) }, label = { Text(folder.name) })
                }
            }
            IconButton(onClick = onNewFolder) { Icon(Icons.Outlined.Add, "新建收藏夹") }
        }
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("共 ${comics?.size ?: 0} 本 · 仅保存在本机", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            var menu by remember { mutableStateOf(false) }
            Box {
                TextButton(onClick = { menu = true }) {
                    Icon(Icons.AutoMirrored.Outlined.Sort, null)
                    Text(if (newestFirst) "新到旧" else "旧到新", Modifier.padding(start = 6.dp))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    listOf(true to "加入时间：新到旧", false to "加入时间：旧到新").forEach { (value, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            trailingIcon = if (value == newestFirst) { { Icon(Icons.Outlined.Check, "当前排序") } } else null,
                            onClick = { menu = false; onSort(value) },
                        )
                    }
                }
            }
        }
        when {
            comics == null || folders == null -> LoadingState(Modifier.weight(1f))
            comics.isEmpty() -> EmptyState(
                title = "还没有本地收藏",
                description = "在漫画详情页点「本地收藏」，离线时也能浏览收藏列表",
                icon = Icons.Outlined.Folder,
                shape = MaterialShapes.Cookie9Sided,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            else -> LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 112.dp)) {
                items(comics, key = { it.comicId }) { entry ->
                    var menu by remember { mutableStateOf(false) }
                    val cover = remember(entry.thumbFileServer, entry.thumbPath, entry.coverUrl) {
                        container.safeImageUrl(entry.thumbFileServer, entry.thumbPath) ?: entry.coverUrl.takeIf { it.isNotBlank() }
                    }
                    ComicListItem(
                        title = entry.title,
                        coverUrl = cover,
                        subtitle = entry.author.ifBlank { null },
                        tags = remember(entry.categoriesJson) { decodeCategories(entry.categoriesJson).take(3) },
                        pages = "${entry.pagesCount} 页 · ${entry.epsCount} 话",
                        selected = entry.comicId in selection,
                        onClick = { if (selection.isEmpty()) onOpenComic(entry.comicId) else onToggle(entry.comicId) },
                        onLongClick = { onToggle(entry.comicId) },
                        trailingContent = if (selection.isNotEmpty()) null else {
                            {
                                Box {
                                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "更多操作") }
                                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                        DropdownMenuItem(
                                            text = { Text("移动到…") },
                                            leadingIcon = { Icon(Icons.AutoMirrored.Outlined.DriveFileMove, null) },
                                            onClick = { menu = false; onMove(entry) },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("移出本地收藏") },
                                            leadingIcon = { Icon(Icons.Outlined.BookmarkRemove, null) },
                                            onClick = { menu = false; onRemove(listOf(entry.comicId)) },
                                        )
                                    }
                                }
                            }
                        },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }
}
