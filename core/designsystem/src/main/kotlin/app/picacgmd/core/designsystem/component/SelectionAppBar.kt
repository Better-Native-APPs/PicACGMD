package app.picacgmd.core.designsystem.component

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.picacgmd.core.designsystem.R

/**
 * The one multi-select app bar of the app (audit §A5): 「已选 N 本」 on the tinted
 * `secondaryContainer` the comic lists already used, with a close leading action and an optional
 * standard 全选 action. Extra batch actions (下载, 删除, …) go in [actions], in that order.
 *
 * Replaces the three variants that existed — tinted vs plain container, select-all present or not,
 * and the shelf's floating batch toolbar — so every list selects in the same way.
 *
 * @param count number of selected items, shown as 「已选 N 本」.
 * @param onClearSelection the close action; leaves selection mode.
 * @param onSelectAll when set, shows the standard 全选 action before [actions].
 * @param actions further batch actions, e.g. a download or delete icon button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionTopAppBar(
    count: Int,
    onClearSelection: () -> Unit,
    modifier: Modifier = Modifier,
    onSelectAll: (() -> Unit)? = null,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    colors: TopAppBarColors = TopAppBarDefaults.topAppBarColors(
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        titleContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ),
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = { Text(stringResource(R.string.designsystem_selected_count, count)) },
        modifier = modifier,
        navigationIcon = {
            AppIconButton(onClick = onClearSelection) {
                Icon(Icons.Outlined.Close, stringResource(R.string.designsystem_clear_selection))
            }
        },
        actions = {
            if (onSelectAll != null) {
                AppIconButton(onClick = onSelectAll) {
                    Icon(Icons.Outlined.SelectAll, stringResource(R.string.designsystem_select_all))
                }
            }
            actions()
        },
        colors = colors,
        scrollBehavior = scrollBehavior,
    )
}
