package app.picacgmd.core.designsystem.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.picacgmd.core.designsystem.R
import app.picacgmd.core.designsystem.theme.PikapikaTheme
import app.picacgmd.core.designsystem.theme.ThemeChoice

/**
 * Slim tertiary ribbon under the top bar while offline (board 04): 「当前离线，只能阅读已下载内容」
 * with a trailing 「去下载」. Expands / collapses with the expressive spatial spring. When
 * [onAction] is set the whole ribbon is the tap target.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun OfflineBanner(
    visible: Boolean,
    modifier: Modifier = Modifier,
    message: String = stringResource(R.string.designsystem_offline_banner),
    actionLabel: String? = stringResource(R.string.designsystem_offline_banner_action),
    onAction: (() -> Unit)? = null,
) {
    val motion = MaterialTheme.motionScheme
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = expandVertically(motion.defaultSpatialSpec()) + fadeIn(motion.defaultEffectsSpec()),
        exit = shrinkVertically(motion.defaultSpatialSpec()) + fadeOut(motion.fastEffectsSpec()),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (onAction != null) {
                            // The whole ribbon is the tap target, so it keeps the 48dp minimum.
                            Modifier
                                .clickable(onClick = onAction, role = Role.Button)
                                .heightIn(min = 48.dp)
                        } else {
                            Modifier
                        },
                    )
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Outlined.WifiOff, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f),
                )
                if (actionLabel != null && onAction != null) {
                    Text(
                        text = actionLabel,
                        style = MaterialTheme.typography.labelMediumEmphasized,
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 380)
@Composable
private fun OfflineBannerPreview() {
    PikapikaTheme(themeChoice = ThemeChoice.Brand) {
        OfflineBanner(visible = true, onAction = {})
    }
}
