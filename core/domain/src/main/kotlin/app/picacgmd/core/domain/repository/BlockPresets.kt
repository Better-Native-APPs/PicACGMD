package app.picacgmd.core.domain.repository

/**
 * A ready-made group of hidden keywords, offered as 「快速添加预设组」 on the keyword settings page.
 * Words that are written differently in Simplified and Traditional Chinese come in both forms.
 */
data class BlockPreset(val name: String, val keywords: List<String>) {
    /** The words of this group that the hidden-keyword list [hidden] does not have yet. */
    fun missingFrom(hidden: List<String>): List<String> = keywords.filterNot(hidden::containsKeyword)
}

/**
 * The preset groups. They stay short on purpose: every word hides comics in lists, so each one has to
 * mean the content it names and little else. Matching rules are those of the keyword filter: 「BL」 and
 * 「NTR」 only match whole words, the others match anywhere in a title, author, category or tag.
 */
object BlockPresets {
    /** The markers behind the 「屏蔽 AI 作品」 switch, for people who would rather manage them as keywords. */
    val Ai: BlockPreset = BlockPreset("AI作画", aiComicMarkers)

    /** 「耽美」 also catches the 耽美花園 category. */
    val Bl: BlockPreset = BlockPreset("耽美相关", listOf("耽美", "BL", "腐向", "男男"))

    /** 「重口」 also catches the 重口地帶 category. */
    val Extreme: BlockPreset = BlockPreset("重口味", listOf("重口", "猎奇", "獵奇", "血腥", "排泄", "人体改造", "人體改造"))

    /** 「NTR」 is also a server category; 「寝取」 catches the Japanese 寝取られ. */
    val Ntr: BlockPreset = BlockPreset("NTR相关", listOf("NTR", "寝取", "寢取", "绿帽", "綠帽"))

    val all: List<BlockPreset> = listOf(Ai, Bl, Extreme, Ntr)
}

/*
 * Edits of the hidden-keyword list shared by the settings page and the detail page's 屏蔽模式. Two
 * spellings that differ only in case or surrounding spaces are the same keyword, since they hide the same
 * comics.
 */

/** The spelling under which [word] is stored in this list, e.g. 「BL」 for 「 bl 」; null when it is not there. */
fun List<String>.findKeyword(word: String): String? {
    val key = word.keywordKey()
    return firstOrNull { it.keywordKey() == key }
}

/** Whether this hidden-keyword list has [word] already. */
fun List<String>.containsKeyword(word: String): Boolean = findKeyword(word) != null

/** This list with the [words] it lacks appended, trimmed and in order; blank words are skipped. */
fun List<String>.plusKeywords(words: Collection<String>): List<String> {
    val known = mapTo(HashSet()) { it.keywordKey() }
    return this + words.map(String::trim).filter { it.isNotEmpty() && known.add(it.keywordKey()) }
}

/** This list without any spelling of the [words]. */
fun List<String>.minusKeywords(words: Collection<String>): List<String> {
    val dropped = words.mapTo(HashSet()) { it.keywordKey() }
    return filterNot { it.keywordKey() in dropped }
}

private fun String.keywordKey(): String = trim().lowercase()
