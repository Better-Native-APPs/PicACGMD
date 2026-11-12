package app.picacgmd.feature.reader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.picacgmd.feature.reader.R

/**
 * The only thing on screen while reading immersively: 「14 / 22」 in a small translucent pill, or
 * 「14-15 / 22」 while a two-page spread shows pages [page] to [lastPage] (1-based).
 */
@Composable
internal fun PageBadge(page: Int, pageCount: Int, modifier: Modifier = Modifier, lastPage: Int = page) {
    Box(
        modifier = modifier
            .heightIn(min = 18.dp)
            .clip(MaterialTheme.shapes.small)
            .background(ReaderPillColors.Badge)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (lastPage > page) {
                stringResource(R.string.reader_spread_badge, page, lastPage, pageCount)
            } else {
                stringResource(R.string.reader_page_badge, page, pageCount)
            },
            style = MaterialTheme.typography.labelSmall
                .copy(fontSize = 11.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
                .withTabularFigures(),
            color = ReaderPillColors.Content,
        )
    }
}
