package app.picacgmd.core.designsystem.component

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import app.picacgmd.core.designsystem.R
import app.picacgmd.core.designsystem.theme.PikapikaTheme
import app.picacgmd.core.designsystem.theme.ThemeChoice
import kotlinx.coroutines.launch

/** Container tone of the [StatusPane] shape. */
enum class StatusTone { Neutral, Error }

/**
 * Centered status block shared by empty, error and offline states (board 04 `status()`):
 * a 96dp decorative MaterialShapes badge with an icon, a title, an optional description and an
 * optional row of buttons. The badge settles in with the expressive spatial spring on first show.
 *
 * Shape semantics (board 01): Cookie9Sided = default / error, Clover8Leaf = offline or no search
 * results, Sunny = nothing downloaded, Clover4Leaf = no comments or shielded content.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun StatusPane(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    icon: ImageVector = Icons.Outlined.AutoAwesome,
    shape: RoundedPolygon = MaterialShapes.Cookie9Sided,
    tone: StatusTone = StatusTone.Neutral,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val container = if (tone == StatusTone.Error) colors.errorContainer else colors.secondaryContainer
    val content = if (tone == StatusTone.Error) colors.onErrorContainer else colors.onSecondaryContainer
    val inPreview = LocalInspectionMode.current
    val scale = remember { Animatable(if (inPreview) 1f else 0.82f) }
    val rotation = remember { Animatable(if (inPreview) 0f else -24f) }
    val spatial = MaterialTheme.motionScheme.slowSpatialSpec<Float>()
    LaunchedEffect(Unit) {
        launch { scale.animateTo(1f, spatial) }
        launch { rotation.animateTo(0f, spatial) }
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                    rotationZ = rotation.value
                }
                .clip(shape.toShape())
                .background(container),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = content,
                modifier = Modifier
                    .size(36.dp)
                    .graphicsLayer { rotationZ = -rotation.value },
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLargeEmphasized,
            color = colors.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(top = 8.dp)
                .semantics { heading() },
        )
        if (description != null) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (actions != null) {
            Row(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = actions,
            )
        }
    }
}

/** Page-level loading: [ContainedLoadingIndicator] with an optional caption. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LoadingState(
    modifier: Modifier = Modifier,
    message: String? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ContainedLoadingIndicator()
        if (message != null) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Load failure with a filled 「重试」 button and an optional tonal secondary action, e.g. 「切换分流」.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ErrorState(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = stringResource(R.string.designsystem_error_title),
    description: String? = stringResource(R.string.designsystem_error_description),
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
) {
    StatusPane(
        title = title,
        description = description,
        icon = Icons.Outlined.CloudOff,
        shape = MaterialShapes.Cookie9Sided,
        tone = StatusTone.Error,
        modifier = modifier,
        actions = {
            if (secondaryActionLabel != null && onSecondaryAction != null) {
                AppFilledTonalButton(onClick = onSecondaryAction) {
                    Text(secondaryActionLabel)
                }
            }
            AppButton(onClick = onRetry) {
                Text(stringResource(R.string.designsystem_retry))
            }
        },
    )
}

/** Nothing to show yet, with an optional tonal action (e.g. 「清除筛选」). */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    icon: ImageVector = Icons.Outlined.AutoAwesome,
    shape: RoundedPolygon = MaterialShapes.Cookie9Sided,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    StatusPane(
        title = title,
        description = description,
        icon = icon,
        shape = shape,
        modifier = modifier,
        actions = if (actionLabel != null && onAction != null) {
            {
                AppFilledTonalButton(onClick = onAction) {
                    Text(actionLabel)
                }
            }
        } else {
            null
        },
    )
}

/** Full-page 「没有网络连接」 state (Clover8Leaf + wifi_off) with 网络设置 / 重试. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun OfflineState(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = stringResource(R.string.designsystem_offline_title),
    description: String? = stringResource(R.string.designsystem_offline_description),
    onOpenNetworkSettings: (() -> Unit)? = null,
) {
    StatusPane(
        title = title,
        description = description,
        icon = Icons.Outlined.WifiOff,
        shape = MaterialShapes.Clover8Leaf,
        modifier = modifier,
        actions = {
            if (onOpenNetworkSettings != null) {
                AppFilledTonalButton(onClick = onOpenNetworkSettings) {
                    Text(stringResource(R.string.designsystem_network_settings))
                }
            }
            AppButton(onClick = onRetry) {
                Text(stringResource(R.string.designsystem_retry))
            }
        },
    )
}

@Preview(showBackground = true, widthDp = 380)
@Composable
private fun ErrorStatePreview() {
    PikapikaTheme(themeChoice = ThemeChoice.Brand) {
        ErrorState(
            onRetry = {},
            description = "当前分流没有响应。可以换一条分流，或稍后再试。",
            secondaryActionLabel = "切换分流",
            onSecondaryAction = {},
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Preview(showBackground = true, widthDp = 380)
@Composable
private fun EmptyStatePreview() {
    PikapikaTheme(themeChoice = ThemeChoice.Book) {
        EmptyState(
            title = "没找到相关漫画",
            description = "换个关键词，或者去掉一些筛选条件试试。",
            shape = MaterialShapes.Clover8Leaf,
            actionLabel = "清除筛选",
            onAction = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 380)
@Composable
private fun OfflineAndLoadingPreview() {
    PikapikaTheme(themeChoice = ThemeChoice.Dusty) {
        Column {
            OfflineState(onRetry = {}, onOpenNetworkSettings = {})
            LoadingState(message = "正在加载…")
        }
    }
}
