package app.picacgmd.feature.reader.logic

import app.picacgmd.feature.reader.ReaderFilter

/** How strongly 棕褐 tints a page; 1 would be full sepia and flattens line art too much. */
private const val SEPIA_AMOUNT: Float = 0.8f

/** 4×5 colour matrix (row by row: R, G, B, A) of [this] filter, or null for 正常. */
internal fun ReaderFilter.colorMatrix(): FloatArray? = when (this) {
    ReaderFilter.Normal -> null
    ReaderFilter.Grayscale -> floatArrayOf(
        0.213f, 0.715f, 0.072f, 0f, 0f,
        0.213f, 0.715f, 0.072f, 0f, 0f,
        0.213f, 0.715f, 0.072f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )
    ReaderFilter.Sepia -> sepiaMatrix(SEPIA_AMOUNT)
}

private fun sepiaMatrix(amount: Float): FloatArray {
    val rest = 1f - amount
    return floatArrayOf(
        0.393f + 0.607f * rest, 0.769f - 0.769f * rest, 0.189f - 0.189f * rest, 0f, 0f,
        0.349f - 0.349f * rest, 0.686f + 0.314f * rest, 0.168f - 0.168f * rest, 0f, 0f,
        0.272f - 0.272f * rest, 0.534f - 0.534f * rest, 0.131f + 0.869f * rest, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )
}
