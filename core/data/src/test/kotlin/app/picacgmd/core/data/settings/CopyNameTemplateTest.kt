package app.picacgmd.core.data.settings

import kotlin.test.Test
import kotlin.test.assertEquals

class CopyNameTemplateTest {
    @Test fun rendersEveryDocumentedTokenAndRepeatedTokens() {
        assertEquals("[作者] 标题 · 123 · 标题", renderCopyName("[{author}] {title} · {id} · {title}", "作者", "标题", "123"))
    }

    @Test fun keepsUnknownTokensAndNeverReplacesMetadataRecursively() {
        assertEquals("{title} / {author} / {other}", renderCopyName("{author} / {title} / {other}", "{title}", "{author}", "id"))
    }

    @Test fun preservesLiteralAndEmptyValues() {
        assertEquals("[]  标题", renderCopyName("[{author}]  {title}", "", "标题", ""))
        assertEquals("", renderCopyName("", "作者", "标题", "123"))
    }
}
