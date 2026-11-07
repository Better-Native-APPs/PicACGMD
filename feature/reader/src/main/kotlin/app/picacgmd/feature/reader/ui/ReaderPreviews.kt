package app.picacgmd.feature.reader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.picacgmd.feature.reader.ReaderBackground

@Preview(name = "加载中", widthDp = 360, heightDp = 520)
@Composable
private fun PageLoadingPreview() {
    ReaderTheme(ReaderBackground.Black) {
        PageLoadingBlock(pageNumber = 15, reduceMotion = true, modifier = Modifier.fillMaxWidth().height(520.dp))
    }
}

@Preview(name = "加载失败", widthDp = 360, heightDp = 520)
@Composable
private fun PageFailurePreview() {
    ReaderTheme(ReaderBackground.Black) {
        PageFailureBlock(
            pageNumber = 15,
            failure = PageFailure.Timeout,
            onRetry = {},
            onSwitchServer = {},
            modifier = Modifier.fillMaxWidth().height(520.dp),
        )
    }
}

@Preview(name = "页码", widthDp = 200, heightDp = 80)
@Composable
private fun PageBadgePreview() {
    ReaderTheme(ReaderBackground.Paper) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
                .background(ReaderBackground.Paper.palette().background),
        ) {
            PageBadge(page = 14, pageCount = 22, modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp))
        }
    }
}
