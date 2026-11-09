package app.picacgmd.navigation

import kotlinx.serialization.Serializable

// Type-safe navigation routes. Top-level tabs are nested graphs so each keeps its own back stack;
// every other destination lives at the root and is pushed on top of whichever tab opened it.

// ---- Top-level graphs and their start destinations ----

@Serializable data object DiscoverGraph

@Serializable data object DiscoverRoute

@Serializable data object BookshelfGraph

@Serializable data object BookshelfRoute

@Serializable data object DownloadsGraph

@Serializable data object DownloadsRoute

/** 「我的」: reached from the discover avatar on phones, a rail item on large screens. */
@Serializable data object MeGraph

@Serializable data object MeRoute

// ---- Shared destinations ----

@Serializable data object SettingsRoute

@Serializable data object DownloadSettingsRoute

@Serializable data object MyCommentsRoute

/** 公告: official announcement list with in-place detail. */
@Serializable data object AnnouncementsRoute

@Serializable data class SearchRoute(val keyword: String? = null)

/** Comic list filtered by category, tag, author, team or uploader; all null = every comic. */
@Serializable
data class ComicListRoute(
    val category: String? = null,
    val tag: String? = null,
    val author: String? = null,
    val chineseTeam: String? = null,
    val creatorId: String? = null,
    val title: String? = null,
)

@Serializable data class ComicDetailRoute(val comicId: String)

/** @param title top bar title when this comment section has a name of its own (the message board). */
@Serializable data class ComicCommentsRoute(val comicId: String, val title: String? = null)

@Serializable
data class ReaderRoute(
    val comicId: String,
    val episodeOrder: Int = 1,
    val page: Int = 0,
)

@Serializable data class DownloadedReaderRoute(val comicId: String)

@Serializable data object RankingsRoute

@Serializable data object RandomRoute

@Serializable data object GamesRoute

@Serializable data class GameDetailRoute(val gameId: String)

@Serializable data class GameCommentsRoute(val gameId: String, val title: String = "游戏评论")

@Serializable data class GameRepliesRoute(val gameId: String, val commentId: String)

/** 分类管理: home category pins, order and visibility. */
@Serializable data object CategoriesRoute
