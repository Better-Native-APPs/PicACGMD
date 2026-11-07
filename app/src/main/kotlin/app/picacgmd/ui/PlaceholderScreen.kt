package app.picacgmd.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.picacgmd.R
import app.picacgmd.core.designsystem.component.EmptyState
import app.picacgmd.core.designsystem.theme.PikapikaTheme
import app.picacgmd.core.designsystem.theme.ThemeChoice

/** A navigation shortcut offered by a placeholder page. */
@Immutable
data class PlaceholderLink(val label: String, val onClick: () -> Unit)

/**
 * Stand-in for screens that are not built yet: a top app bar with the page title and a status
 * pane, plus [links] to the pages it will lead to so the navigation graph can be exercised.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PlaceholderScreen(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    links: List<PlaceholderLink> = emptyList(),
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Outlined.ArrowBack,
                                contentDescription = stringResource(R.string.action_back),
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            EmptyState(
                title = stringResource(R.string.placeholder_title),
                description = stringResource(R.string.placeholder_description),
            )
            links.forEach { link ->
                FilledTonalButton(onClick = link.onClick, shapes = ButtonDefaults.shapes()) {
                    Text(link.label)
                }
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 412, heightDp = 640)
@Composable
private fun PlaceholderScreenPreview() {
    PikapikaTheme(themeChoice = ThemeChoice.Brand) {
        PlaceholderScreen(
            title = "漫画详情",
            onBack = {},
            links = listOf(PlaceholderLink("开始阅读") {}),
        )
    }
}
