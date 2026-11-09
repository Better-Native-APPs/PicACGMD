package app.picacgmd.core.designsystem.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.picacgmd.core.designsystem.R

/** A consistent, RTL-aware navigation affordance for every non-root page. */
@Composable
fun BackButton(onBack: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onBack, modifier = modifier) {
        Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back))
    }
}
